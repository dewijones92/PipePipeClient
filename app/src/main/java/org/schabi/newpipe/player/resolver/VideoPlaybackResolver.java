package org.schabi.newpipe.player.resolver;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.exoplayer.source.SingleSampleMediaSource;

import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.player.datasource.SabrSessionStore;
import org.schabi.newpipe.player.helper.PlayerDataSource;
import org.schabi.newpipe.player.mediasource.FastStartMediaSource;
import org.schabi.newpipe.player.helper.PlayerHelper;
import org.schabi.newpipe.player.mediaitem.MediaItemTag;
import org.schabi.newpipe.player.mediaitem.StreamInfoTag;
import org.schabi.newpipe.util.ListHelper;
import org.schabi.newpipe.util.YtdlpBridge;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static androidx.media3.common.C.TIME_UNSET;
import static org.schabi.newpipe.util.ListHelper.*;

public class VideoPlaybackResolver implements PlaybackResolver {

    @NonNull
    private final Context context;
    @NonNull
    private final PlayerDataSource dataSource;
    @NonNull
    private final QualityResolver qualityResolver;
    private SourceType streamSourceType;

    private int selectedIndex = -1;
    @Nullable
    private String audioTrack;
    /** Resume/recovery position (ms) to start a yt-dlp bridge session at; 0 = from the beginning. */
    private long bridgeStartPositionMs;

    private List<String> blacklistUrls = new ArrayList<>();

    public enum SourceType {
        LIVE_STREAM,
        VIDEO_WITH_SEPARATED_AUDIO,
        VIDEO_WITH_AUDIO_OR_AUDIO_ONLY
    }

    /** Invoked on the main thread when a fast-start bridge warm-up is ready to be adopted. */
    @Nullable
    private final Runnable fastStartUpgradeListener;

    public VideoPlaybackResolver(@NonNull final Context context,
                                 @NonNull final PlayerDataSource dataSource,
                                 @NonNull final QualityResolver qualityResolver) {
        this(context, dataSource, qualityResolver, null);
    }

    public VideoPlaybackResolver(@NonNull final Context context,
                                 @NonNull final PlayerDataSource dataSource,
                                 @NonNull final QualityResolver qualityResolver,
                                 @Nullable final Runnable fastStartUpgradeListener) {
        this.context = context;
        this.dataSource = dataSource;
        this.qualityResolver = qualityResolver;
        this.fastStartUpgradeListener = fastStartUpgradeListener;
    }

    @Override
    @Nullable
    public MediaSource resolve(@NonNull final StreamInfo info) {
        final MediaSource liveSource = PlaybackResolver.maybeBuildLiveMediaSource(dataSource, info);
        if (liveSource != null) {
            streamSourceType = SourceType.LIVE_STREAM;
            return liveSource;
        }

        // Hand the user-selected audio language to the SABR session store before it (re)builds the
        // session for this video, so the switch actually changes the streamed track.
        SabrSessionStore.setPreferredAudioTrack(info.getId(), audioTrack);

        final List<MediaSource> mediaSources = new ArrayList<>();
        final List<VideoStream> videoStreams = new ArrayList<>(info.getVideoStreams());
        final List<VideoStream> videoOnlyStreams = new ArrayList<>(info.getVideoOnlyStreams());
        final List<AudioStream> playbackAudioStreams = new ArrayList<>(info.getAudioStreams());

        final boolean hasSabr = videoStreams.stream().anyMatch(
                stream -> stream.getDeliveryMethod() == DeliveryMethod.SABR)
                || videoOnlyStreams.stream().anyMatch(
                stream -> stream.getDeliveryMethod() == DeliveryMethod.SABR)
                || playbackAudioStreams.stream().anyMatch(
                stream -> stream.getDeliveryMethod() == DeliveryMethod.SABR);
        if (hasSabr) {
            videoStreams.removeIf(stream -> stream.getDeliveryMethod() == DeliveryMethod.HLS);
            videoOnlyStreams.removeIf(stream -> stream.getDeliveryMethod() == DeliveryMethod.HLS);
            playbackAudioStreams.removeIf(
                    stream -> stream.getDeliveryMethod() == DeliveryMethod.HLS);
        }

        removeTorrentStreams(videoStreams);
        removeTorrentStreams(videoOnlyStreams);

        // Create video stream source
        List<VideoStream> videos = ListHelper.getSortedStreamVideosList(context,
                videoStreams, videoOnlyStreams, false, true)
                .stream().filter(s -> !blacklistUrls.contains(s.getContent())).collect(Collectors.toList());

        if (audioTrack != null) {
            final List<VideoStream> filtered = videos.stream()
                    .filter(s -> audioTrack.equals(s.getAudioTrackId()))
                    .collect(Collectors.toList());
            if (!filtered.isEmpty()) {
                videos = filtered;
            }
        } else {
            final boolean hasVideoAudioTracks = videos.stream()
                    .anyMatch(s -> s.getAudioTrackId() != null);
            if (hasVideoAudioTracks) {
                final List<AudioStream> allAudioStreams = ListHelper.getFilteredAudioStreams(
                        context, playbackAudioStreams);
                final int defaultIdx = ListHelper.getDefaultAudioFormat(context, allAudioStreams);
                if (defaultIdx >= 0 && defaultIdx < allAudioStreams.size()) {
                    final String defaultTrackId = allAudioStreams.get(defaultIdx).getAudioTrackId();
                    if (defaultTrackId != null) {
                        final List<VideoStream> filtered = videos.stream()
                                .filter(s -> defaultTrackId.equals(s.getAudioTrackId()))
                                .collect(Collectors.toList());
                        if (!filtered.isEmpty()) {
                            videos = filtered;
                        }
                    }
                }
            }
        }
        final int index;
        if (videos.isEmpty()) {
            index = -1;
        } else if (selectedIndex == -1) {
            index = qualityResolver.getDefaultResolutionIndex(videos);
        } else {
            index = qualityResolver.getOverrideResolutionIndex(videos, selectedIndex);
        }
        final MediaItemTag tag = StreamInfoTag.of(info, videos, index);
        @Nullable final VideoStream video = tag.getMaybeQuality()
                .map(MediaItemTag.Quality::getSelectedVideoStream)
                .orElse(null);

        // Audio selection is computed before the video source: both the separated-audio merge
        // and the yt-dlp local bridge (which muxes the audio in) need it.
        final List<AudioStream> audioStreams = ListHelper.getFilteredAudioStreams(context,
                playbackAudioStreams
                        .stream().filter(s -> !blacklistUrls.contains(s.getContent()))
                        .collect(Collectors.toList()));
        final int audioIndex = ListHelper.getAudioFormatIndex(context, audioStreams, audioTrack);
        final AudioStream audio = audioStreams.isEmpty() || audioIndex == -1
                ? null : audioStreams.get(audioIndex);

        // yt-dlp adaptive video-only streams are not directly playable (fragmented MP4 / adaptive
        // WebM without init/index ranges fail as progressive media). Deliver through the local
        // bridge instead: the bundled ffmpeg remuxes video+audio into a local HLS playlist and
        // ExoPlayer plays only the local files. The download starts lazily on first period
        // creation, so preloaded queue neighbours don't spawn ffmpeg. Audio is muxed into the
        // bridge output, so no separate audio source is merged.
        final boolean bridgedDelivery = video != null && video.getDeliveryMethod()
                == org.schabi.newpipe.extractor.stream.DeliveryMethod.YTDLP;
        if (bridgedDelivery) {
            // Fast start: a cold bridge needs a multi-second ffmpeg warm-up before anything
            // renders, while a progressive muxed stream reaches READY in under a second. On a
            // cold from-zero start, play the progressive stream NOW, warm the bridge behind it
            // (kicked on first real play so preloaded queue neighbours don't spawn ffmpeg), and
            // let the Player reload onto the warm bridge — the rebuild lands in the else-branch
            // below and adopts the warm session.
            // Pick from the RAW muxed list: the sorted quality list dedupes per resolution in
            // favour of video-only variants, so the progressive stream is usually absent there.
            final VideoStream fastStart = fastStartUpgradeListener == null ? null
                    : pickFastStartStream(info.getVideoStreams(), video);
            MediaSource fastStartSource = null;
            if (fastStart != null
                    && YtdlpBridge.wouldColdStart(video.getContent(), bridgeStartPositionMs)) {
                try {
                    final MediaItemTag fastTag =
                            StreamInfoTag.of(info, videos, videos.indexOf(fastStart));
                    final MediaSource progressive = PlaybackResolver.buildMediaSource(
                            dataSource, fastStart, info,
                            PlayerHelper.cacheKeyOf(info, fastStart), fastTag);
                    final VideoStream bridgeVideo = video;
                    final AudioStream bridgeAudio = audio;
                    fastStartSource = new FastStartMediaSource(progressive, () ->
                            YtdlpBridge.preWarmFastStart(context, bridgeVideo, bridgeAudio,
                                    fastStartUpgradeListener));
                } catch (final IOException e) {
                    // Progressive stand-in failed to build; fall through to the plain bridge.
                }
            }
            if (fastStartSource != null) {
                mediaSources.add(fastStartSource);
                streamSourceType = SourceType.VIDEO_WITH_AUDIO_OR_AUDIO_ONLY;
            } else {
                mediaSources.add(YtdlpBridge.buildBridgedSource(context, video, audio, tag,
                        bridgeStartPositionMs));
                streamSourceType = audio != null
                        ? SourceType.VIDEO_WITH_SEPARATED_AUDIO
                        : SourceType.VIDEO_WITH_AUDIO_OR_AUDIO_ONLY;
            }
        }

        if (!bridgedDelivery && video != null) {
            try {
                final MediaSource streamSource = PlaybackResolver.buildMediaSource(
                        dataSource, video, info, PlayerHelper.cacheKeyOf(info, video), tag);
                mediaSources.add(streamSource);
            } catch (final IOException e) {
                // For SABR, surface the real failure (probe / session creation) with its cause
                // instead of swallowing it into a generic "Unable to resolve source from stream info"
                // downstream where you can't tell where it came from. Non-SABR keeps returning null
                // (sourceOf then falls back to the audio source), so that path is unchanged.
                if (video.getDeliveryMethod()
                        == org.schabi.newpipe.extractor.stream.DeliveryMethod.SABR) {
                    throw new IllegalStateException(
                            "Unable to create SABR video source for " + info.getUrl(), e);
                }
                return null;
            }
        }

        // Use the audio stream if there is no video stream, or
        // merge with audio stream in case if video does not contain audio
        // SABR carries audio + video in one MediaSource, so don't add a separate audio source.
        final boolean videoIsSabr = video != null && video.getDeliveryMethod()
                == org.schabi.newpipe.extractor.stream.DeliveryMethod.SABR;
        final boolean videoHasMatchingAudio = video != null && !video.isVideoOnly()
                && audioTrack != null && audioTrack.equals(video.getAudioTrackId());
        if (!bridgedDelivery) {
            if (audio != null && !videoHasMatchingAudio && !videoIsSabr
                    && (video == null || video.isVideoOnly() || audioTrack != null)) {
                try {
                    final MediaSource audioSource = PlaybackResolver.buildMediaSource(
                            dataSource, audio, info, PlayerHelper.cacheKeyOf(info, audio), tag);
                    mediaSources.add(audioSource);
                    streamSourceType = SourceType.VIDEO_WITH_SEPARATED_AUDIO;
                } catch (final IOException e) {
                    return null;
                }
            } else {
                streamSourceType = SourceType.VIDEO_WITH_AUDIO_OR_AUDIO_ONLY;
            }
        }

        // If there is no audio or video sources, then this media source cannot be played back
        if (mediaSources.isEmpty()) {
            return null;
        }
        // Below are auxiliary media sources

        // Create subtitle sources
        final List<SubtitlesStream> subtitlesStreams = info.getSubtitles();
        if (subtitlesStreams != null) {
            // Torrent and non URL subtitles are not supported by ExoPlayer
            final List<SubtitlesStream> nonTorrentAndUrlStreams = removeNonUrlAndTorrentStreams(
                    subtitlesStreams);
            for (final SubtitlesStream subtitle : nonTorrentAndUrlStreams) {
                final MediaFormat mediaFormat = subtitle.getFormat();
                if (mediaFormat != null) {
                    @C.RoleFlags final int textRoleFlag = subtitle.isAutoGenerated()
                            ? C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND
                            : C.ROLE_FLAG_CAPTION;
                    if(!subtitle.isUrl()){
                        final MediaItem.SubtitleConfiguration textMediaItem =
                                new MediaItem.SubtitleConfiguration.Builder(
                                        Uri.parse(""))
                                        .setMimeType(mediaFormat.getMimeType())
                                        .setRoleFlags(textRoleFlag)
                                        .setLanguage(PlayerHelper.captionLanguageOf(context, subtitle))
                                        .build();
                        final MediaSource textSource =
                                new SingleSampleMediaSource.Factory(new CustomDataSourceFactory(context, null, subtitle.getContent().getBytes()))
                                        .createMediaSource(textMediaItem, C.TIME_UNSET);
                        mediaSources.add(textSource);
                        continue;
                    }
                    final MediaItem.SubtitleConfiguration textMediaItem =
                            new MediaItem.SubtitleConfiguration.Builder(
                                    Uri.parse(subtitle.getContent()))
                                    .setMimeType(mediaFormat.getMimeType())
                                    .setRoleFlags(textRoleFlag)
                                    .setLanguage(PlayerHelper.captionLanguageOf(context, subtitle))
                                    .build();
                    final MediaSource textSource = dataSource.getSingleSampleMediaSourceFactory()
                            .createMediaSource(textMediaItem, TIME_UNSET);
                    mediaSources.add(textSource);
                }
            }
        }

        if (mediaSources.size() == 1) {
            return mediaSources.get(0);
        } else {
            return new MergingMediaSource(true, mediaSources.toArray(new MediaSource[0]));
        }
    }

    /**
     * The progressive (muxed, directly playable) stand-in for a fast start: the highest-resolution
     * muxed stream not above the bridge selection, or the best muxed one available. Null when the
     * video has no plain progressive stream (then a fast start isn't possible).
     */
    @Nullable
    private static VideoStream pickFastStartStream(@NonNull final List<VideoStream> videos,
                                                   @NonNull final VideoStream selected) {
        VideoStream bestUnderSelected = null;
        VideoStream lowest = null;
        for (final VideoStream s : videos) {
            if (s.isVideoOnly()
                    || s.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP
                    || s.getContent().isEmpty()) {
                continue;
            }
            if (lowest == null || heightOf(s) < heightOf(lowest)) {
                lowest = s;
            }
            if (heightOf(s) <= heightOf(selected)
                    && (bestUnderSelected == null || heightOf(s) > heightOf(bestUnderSelected))) {
                bestUnderSelected = s;
            }
        }
        return bestUnderSelected != null ? bestUnderSelected : lowest;
    }

    private static int heightOf(@NonNull final VideoStream stream) {
        try {
            return Integer.parseInt(stream.getResolution().replaceAll("[^0-9].*$", ""));
        } catch (final NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Returns the last resolved {@link StreamInfo}'s {@link SourceType source type}.
     *
     * @return {@link Optional#empty()} if nothing was resolved, otherwise the {@link SourceType}
     * of the last resolved {@link StreamInfo} inside an {@link Optional}
     */
    public Optional<SourceType> getStreamSourceType() {
        return Optional.ofNullable(streamSourceType);
    }

    public int getSelectedIndex() {
        return selectedIndex;
    }

    public void setSelectedIndex(int selectedIndex) {
        this.selectedIndex = selectedIndex;
    }

    public void addBlacklistUrl(@NonNull final String url) {
        blacklistUrls.add(url);
    }

    public List<String> getBlacklistUrls() {
        return blacklistUrls;
    }

    @Nullable
    public String getAudioTrack() {
        return audioTrack;
    }

    public void setAudioTrack(@Nullable final String audioTrack) {
        this.audioTrack = audioTrack;
    }

    /**
     * Resume position (ms) the next resolved yt-dlp bridge source should mux from, so a recovery
     * seek to it lands in muxed content rather than far ahead of a from-zero mux edge. Set per
     * resolve from the play-queue item's recovery position; 0 means start from the beginning.
     */
    public void setBridgeStartPositionMs(final long bridgeStartPositionMs) {
        this.bridgeStartPositionMs = bridgeStartPositionMs;
    }
}
