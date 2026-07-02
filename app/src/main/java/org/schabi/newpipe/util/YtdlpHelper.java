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
import org.schabi.newpipe.extractor.localization.DateWrapper;
import org.schabi.newpipe.extractor.services.youtube.ItagItem;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.Description;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
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
                noDashRange(itag);
                final AudioStream.Builder audioBuilder = new AudioStream.Builder()
                        .setId(info.getId() + UUID.randomUUID().toString().replaceAll("[^a-zA-Z]", ""))
                        .setContent(pppUrl, true)
                        .setItagItem(itag)
                        .setMediaFormat(format)
                        .setAverageBitrate(f.getTotalBitrateKbps());
                final String audioLang = f.getLanguage();
                if (audioLang != null && !audioLang.isEmpty()) {
                    // Multi-audio / dubbed track: expose the language for track selection.
                    audioBuilder.setAudioTrackId(audioLang)
                            .setAudioTrackName(f.getFormatNote() != null ? f.getFormatNote() : audioLang)
                            .setAudioLocale(audioLang);
                }
                audioStreams.add(audioBuilder.build());
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
                noDashRange(itag);
                final VideoStream.Builder videoBuilder = new VideoStream.Builder()
                        .setContent(pppUrl, true)
                        .setMediaFormat(format)
                        .setId(info.getId())
                        .setItagItem(itag)
                        .setIsVideoOnly(videoOnly)
                        .setResolution(resolution);
                if (videoOnly) {
                    // Adaptive video-only formats (fragmented MP4 / adaptive WebM, no init/index
                    // ranges) can't be fed to ExoPlayer as progressive media — they fail with
                    // ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED on real devices. Mark them for the
                    // local bridge: VideoPlaybackResolver remuxes them via the bundled ffmpeg into
                    // a local HLS playlist and plays that instead of this URL.
                    videoBuilder.setDeliveryMethod(DeliveryMethod.YTDLP);
                }
                (videoOnly ? videoOnlyStreams : videoStreams).add(videoBuilder.build());
            }
        }
        Collections.sort(audioStreams, Comparator.comparingInt(AudioStream::getBitrate).reversed());

        final boolean audioOnly = !audioStreams.isEmpty()
                && videoStreams.isEmpty() && videoOnlyStreams.isEmpty();
        if (info.isLive()) {
            streamInfo.setStreamType(audioOnly
                    ? StreamType.AUDIO_LIVE_STREAM : StreamType.LIVE_STREAM);
            final String hlsUrl = firstManifestUrl(info);
            if (hlsUrl != null) {
                streamInfo.setHlsUrl(hlsUrl);
            }
        } else {
            streamInfo.setStreamType(audioOnly ? StreamType.AUDIO_STREAM : StreamType.VIDEO_STREAM);
        }

        streamInfo.setName(info.getTitle() == null ? "" : info.getTitle());
        streamInfo.setAgeLimit(0);
        streamInfo.setThumbnailUrl(info.getThumbnailUrl());
        streamInfo.setDuration(info.getDurationSeconds());
        streamInfo.setUploaderName(info.getUploader());
        applyMetadata(streamInfo, info);
        streamInfo.setAudioStreams(audioStreams);
        streamInfo.setVideoStreams(videoStreams);
        streamInfo.setVideoOnlyStreams(videoOnlyStreams);
        streamInfo.setSubtitles(buildSubtitles(info));
        return streamInfo;
    }

    /** First HLS/DASH manifest URL among the formats (used as the live stream URL). */
    private static String firstManifestUrl(final MediaInfo info) {
        for (final com.dewijones92.ytdlpkt.MediaFormat f : info.getFormats()) {
            final String m = f.getManifestUrl();
            if (m != null && !m.isEmpty()) {
                return m;
            }
        }
        return null;
    }

    /** Map ytdlp-kt subtitles/auto-captions to NewPipe SubtitlesStreams (mediaFormat may be null). */
    private static ArrayList<SubtitlesStream> buildSubtitles(final MediaInfo info) {
        final ArrayList<SubtitlesStream> out = new ArrayList<>();
        for (final com.dewijones92.ytdlpkt.MediaSubtitle s : info.getSubtitles()) {
            if (s.getUrl() == null || s.getUrl().isEmpty()) {
                continue;
            }
            out.add(new SubtitlesStream.Builder()
                    .setId(s.getLanguageCode() + (s.getAutoGenerated() ? "-auto" : ""))
                    .setContent(s.getUrl(), true)
                    .setMediaFormat(MediaFormat.getFromSuffix(s.getExt()))
                    .setLanguageCode(s.getLanguageCode())
                    .setAutoGenerated(s.getAutoGenerated())
                    .build());
        }
        return out;
    }

    /** #17 Cycle A: map ytdlp-kt MediaInfo metadata onto the NewPipe StreamInfo. */
    private static void applyMetadata(final StreamInfo streamInfo, final MediaInfo info) {
        final String description = info.getDescription();
        if (description != null && !description.isEmpty()) {
            streamInfo.setDescription(new Description(description, Description.PLAIN_TEXT));
        }
        streamInfo.setViewCount(info.getViewCount());
        streamInfo.setLikeCount(info.getLikeCount());
        streamInfo.setDislikeCount(info.getDislikeCount());

        final String uploadDate = info.getUploadDate();
        if (uploadDate != null && uploadDate.length() == 8) {
            streamInfo.setTextualUploadDate(uploadDate);
            try {
                streamInfo.setUploadDate(new DateWrapper(LocalDate
                        .parse(uploadDate, DateTimeFormatter.BASIC_ISO_DATE)
                        .atStartOfDay().atOffset(ZoneOffset.UTC)));
            } catch (final RuntimeException ignored) {
                // unparseable date: keep the textual form, skip the structured one
            }
        }

        final List<String> categories = info.getCategories();
        if (categories != null && !categories.isEmpty()) {
            streamInfo.setCategory(categories.get(0));
        }
        final List<String> tags = info.getTags();
        if (tags != null && !tags.isEmpty()) {
            streamInfo.setTags(new ArrayList<>(tags));
        }
        final String uploaderUrl = channelUrlFromUploaderId(info.getUploaderId());
        if (uploaderUrl != null) {
            streamInfo.setUploaderUrl(uploaderUrl);
        }
    }

    /** Best-effort channel URL from yt-dlp's uploader_id ("@handle" or a "UC..." channel id). */
    private static String channelUrlFromUploaderId(final String uploaderId) {
        if (uploaderId == null || uploaderId.isEmpty()) {
            return null;
        }
        if (uploaderId.startsWith("@")) {
            return "https://www.youtube.com/" + uploaderId;
        }
        if (uploaderId.startsWith("UC")) {
            return "https://www.youtube.com/channel/" + uploaderId;
        }
        return null;
    }

    private static void noDashRange(final ItagItem itag) {
        // yt-dlp doesn't give us DASH init/index byte-ranges here; set -1 so ExoPlayer streams the
        // URL progressively instead of attempting an invalid subrange (IllegalArgumentException in
        // DataSpec.subrange / InitializationChunk.load).
        itag.setInitStart(-1);
        itag.setInitEnd(-1);
        itag.setIndexStart(-1);
        itag.setIndexEnd(-1);
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
