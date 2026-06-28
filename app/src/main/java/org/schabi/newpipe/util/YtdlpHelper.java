package org.schabi.newpipe.util;

import com.dewijones92.ytdlpkt.MediaInfo;
import com.dewijones92.ytdlpkt.YtdlpKt;

import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.AntiBotException;
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.exceptions.PrivateContentException;
import org.schabi.newpipe.extractor.services.youtube.ItagItem;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.UUID;

/**
 * Resolves YouTube streams via our from-source yt-dlp API-23 stack (the typed ytdlp-kt SDK) and maps
 * the result into a NewPipe {@link StreamInfo}. Restored + rewired from PipePipe's previously
 * dropped yt-dlp integration; the mapping now reads ytdlp-kt's {@code MediaInfo}/{@code MediaFormat}
 * instead of the raw youtubedl-android VideoInfo. PoC scope: produces playable audio/video streams
 * plus core metadata (title/duration/thumbnail/uploader).
 */
public class YtdlpHelper {

    public static StreamInfo getFallbackStreams(final String url) throws IOException, ExtractionException {
        try {
            // YtdlpKt.resolve is a suspend fun; resolveBlocking is the synchronous entry point. This
            // runs inside Single.fromCallable (a background scheduler), so blocking here is fine.
            final MediaInfo info = YtdlpKt.resolveBlocking(url);
            return parseInfo(info, url);
        } catch (final Exception e) {
            final String msg = e.getMessage();
            if (msg == null) {
                throw new ExtractionException(e);
            }
            if (msg.contains("Sign in")) {
                throw new AntiBotException(msg, e);
            } else if (msg.contains("in your country")) {
                throw new GeographicRestrictionException(msg);
            } else if (msg.contains("private")) {
                throw new PrivateContentException(msg);
            }
            throw new ExtractionException(e);
        }
    }

    static StreamInfo parseInfo(final MediaInfo info, final String url) throws ParsingException {
        final StreamInfo streamInfo = new StreamInfo(
                ServiceList.YouTube.getServiceId(),
                ServiceList.YouTube.getStreamLHFactory().getId(url),
                url,
                info.getTitle() == null ? "" : info.getTitle());

        final ArrayList<AudioStream> audioStreams = new ArrayList<>();
        final ArrayList<VideoStream> videoStreams = new ArrayList<>();
        final ArrayList<VideoStream> videoOnlyStreams = new ArrayList<>();

        for (final com.dewijones92.ytdlpkt.MediaFormat f : info.getFormats()) {
            final String vcodec = f.getVcodec();
            final String acodec = f.getAcodec();
            final boolean vNone = vcodec == null || vcodec.equals("none");
            final boolean aNone = acodec == null || acodec.equals("none");
            if (vNone && aNone) {
                continue;
            }
            final int itagId = parseItag(f.getFormatId());
            if (itagId < 0) {
                continue;
            }
            final String pppUrl = f.getUrl() + "&pppid=" + info.getId();

            if (vNone) {
                // Audio-only
                final MediaFormat format = "opus".equals(acodec)
                        ? MediaFormat.WEBMA_OPUS : MediaFormat.getFromSuffix(f.getExt());
                final ItagItem itag = new ItagItem(itagId, ItagItem.ItagType.AUDIO,
                        format, f.getTotalBitrateKbps());
                itag.setCodec(acodec);
                itag.setBitrate(f.getTotalBitrateKbps());
                itag.setSampleRate(f.getAudioSampleRate());
                audioStreams.add(new AudioStream.Builder()
                        .setId(info.getId() + UUID.randomUUID().toString().replaceAll("[^a-zA-Z]", ""))
                        .setContent(pppUrl, true)
                        .setItagItem(itag)
                        .setMediaFormat(format)
                        .setAverageBitrate(f.getTotalBitrateKbps())
                        .build());
            } else {
                // Video (muxed) or video-only
                final boolean videoOnly = aNone;
                final MediaFormat format = MediaFormat.getFromSuffix(f.getExt());
                String resolution = f.getFormatNote();
                if (resolution == null) {
                    resolution = f.getHeight() + "p";
                }
                final ItagItem itag = new ItagItem(itagId, ItagItem.ItagType.VIDEO_ONLY,
                        format, resolution, f.getFps());
                itag.setCodec(vcodec);
                itag.setBitrate(f.getTotalBitrateKbps());
                itag.setWidth(f.getWidth());
                itag.setHeight(f.getHeight());
                final VideoStream stream = new VideoStream.Builder()
                        .setContent(pppUrl, true)
                        .setMediaFormat(format)
                        .setId(info.getId())
                        .setItagItem(itag)
                        .setIsVideoOnly(videoOnly)
                        .setResolution(resolution)
                        .build();
                (videoOnly ? videoOnlyStreams : videoStreams).add(stream);
            }
        }
        Collections.sort(audioStreams, Comparator.comparingInt(AudioStream::getBitrate).reversed());

        if (!audioStreams.isEmpty() && videoStreams.isEmpty() && videoOnlyStreams.isEmpty()) {
            streamInfo.setStreamType(StreamType.AUDIO_STREAM);
        } else {
            streamInfo.setStreamType(StreamType.VIDEO_STREAM);
        }

        streamInfo.setName(info.getTitle() == null ? "" : info.getTitle());
        streamInfo.setAgeLimit(0);
        streamInfo.setThumbnailUrl(info.getThumbnailUrl());
        streamInfo.setDuration(info.getDurationSeconds());
        streamInfo.setUploaderName(info.getUploader());
        streamInfo.setAudioStreams(audioStreams);
        streamInfo.setVideoStreams(videoStreams);
        streamInfo.setVideoOnlyStreams(videoOnlyStreams);
        return streamInfo;
    }

    private static int parseItag(final String formatId) {
        if (formatId == null) {
            return -1;
        }
        try {
            return Integer.parseInt(formatId.split("-")[0]);
        } catch (final NumberFormatException e) {
            return -1;
        }
    }
}
