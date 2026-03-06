package com.github.topi314.lavasrc.spotify;

import com.github.topi314.lavalyrics.AudioLyricsManager;
import com.github.topi314.lavalyrics.lyrics.AudioLyrics;
import com.github.topi314.lavalyrics.lyrics.BasicAudioLyrics;
import com.github.topi314.lavasearch.AudioSearchManager;
import com.github.topi314.lavasearch.result.AudioSearchResult;
import com.github.topi314.lavasearch.result.BasicAudioSearchResult;
import com.github.topi314.lavasrc.ExtendedAudioPlaylist;
import com.github.topi314.lavasrc.LavaSrcTools;
import com.github.topi314.lavasrc.mirror.DefaultMirroringAudioTrackResolver;
import com.github.topi314.lavasrc.mirror.MirroringAudioSourceManager;
import com.github.topi314.lavasrc.mirror.MirroringAudioTrackResolver;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpConfigurable;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.track.*;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInput;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SpotifySourceManager extends MirroringAudioSourceManager implements HttpConfigurable, AudioSearchManager, AudioLyricsManager {

	public static final Pattern URL_PATTERN = Pattern.compile("(https?://)(www\\.)?open\\.spotify\\.com/((?<region>[a-zA-Z-]+)/)?(user/(?<user>[a-zA-Z0-9-_]+)/)?(?<type>track|album|playlist|artist)/(?<identifier>[a-zA-Z0-9-_]+)");
	public static final Pattern RADIO_MIX_QUERY_PATTERN = Pattern.compile("mix:(?<seedType>album|artist|track|isrc):(?<seed>[a-zA-Z0-9-_]+)");
	public static final String SEARCH_PREFIX = "spsearch:";
	public static final String RECOMMENDATIONS_PREFIX = "sprec:";
	public static final String PREVIEW_PREFIX = "spprev:";
	public static final long PREVIEW_LENGTH = 30000;
	public static final String SHARE_URL = "https://spotify.link/";
	public static final int PLAYLIST_MAX_PAGE_ITEMS = 100;
	public static final int ALBUM_MAX_PAGE_ITEMS = 50;
	public static final String CLIENT_API_BASE = "https://spclient.wg.spotify.com/";
	public static final String PARTNER_API_BASE = "https://api-partner.spotify.com/pathfinder/v2/query";
	private static final String HASH_GET_TRACK = "612585ae06ba435ad26369870deaae23b5c8800a256cd8a57e08eddc25a37294";
	private static final String HASH_GET_ALBUM = "b9bfabef66ed756e5e13f68a942deb60bd4125ec1f1be8cc42769dc0259b4b10";
	private static final String HASH_FETCH_PLAYLIST = "bb67e0af06e8d6f52b531f97468ee4acd44cd0f82b988e15c2ea47b1148efc77";
	private static final String HASH_QUERY_ARTIST_OVERVIEW = "35648a112beb1794e39ab931365f6ae4a8d45e65396d641eeda94e4003d41497";
	private static final String HASH_SEARCH_DESKTOP = "fcad5a3e0d5af727fb76966f06971c19cfa2275e6ff7671196753e008611873c";
	private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.6998.178 Spotify/1.2.65.255 Safari/537.36";
	public static final Set<AudioSearchResult.Type> SEARCH_TYPES = Set.of(AudioSearchResult.Type.ALBUM, AudioSearchResult.Type.ARTIST, AudioSearchResult.Type.PLAYLIST, AudioSearchResult.Type.TRACK);
	private static final Logger log = LoggerFactory.getLogger(SpotifySourceManager.class);

	private final HttpInterfaceManager httpInterfaceManager = HttpClientTools.createDefaultThreadLocalManager();
	private final SpotifyTokenTracker tokenTracker;
	private final String countryCode;
	private int playlistPageLimit = 6;
	private int albumPageLimit = 6;
	private boolean localFiles;
	private boolean preferAnonymousToken;

	public SpotifySourceManager(String[] providers, String clientId, String clientSecret, String countryCode, AudioPlayerManager audioPlayerManager) {
		this(clientId, clientSecret, null, countryCode, unused -> audioPlayerManager, new DefaultMirroringAudioTrackResolver(providers));
	}

	public SpotifySourceManager(String[] providers, String clientId, String clientSecret, String countryCode, Function<Void, AudioPlayerManager> audioPlayerManager) {
		this(clientId, clientSecret, null, countryCode, audioPlayerManager, new DefaultMirroringAudioTrackResolver(providers));
	}

	public SpotifySourceManager(String clientId, String clientSecret, String countryCode, AudioPlayerManager audioPlayerManager, MirroringAudioTrackResolver mirroringAudioTrackResolver) {
		this(clientId, clientSecret, null, countryCode, unused -> audioPlayerManager, mirroringAudioTrackResolver);
	}

	public SpotifySourceManager(String clientId, String clientSecret, String countryCode, Function<Void, AudioPlayerManager> audioPlayerManager, MirroringAudioTrackResolver mirroringAudioTrackResolver) {
		this(clientId, clientSecret, null, countryCode, audioPlayerManager, mirroringAudioTrackResolver);
	}

	public SpotifySourceManager(String clientId, String clientSecret, String spDc, String countryCode, Function<Void, AudioPlayerManager> audioPlayerManager, MirroringAudioTrackResolver mirroringAudioTrackResolver) {
		this(clientId, clientSecret, false, spDc, countryCode, audioPlayerManager, mirroringAudioTrackResolver);
	}

	public SpotifySourceManager(String clientId, String clientSecret, boolean preferAnonymousToken, String spDc, String countryCode, Function<Void, AudioPlayerManager> audioPlayerManager, MirroringAudioTrackResolver mirroringAudioTrackResolver) {
		this(clientId, clientSecret, preferAnonymousToken, null, spDc, countryCode, audioPlayerManager, mirroringAudioTrackResolver);
	}

	public SpotifySourceManager(String clientId, String clientSecret, boolean preferAnonymousToken, String customTokenEndpoint, String spDc, String countryCode, Function<Void, AudioPlayerManager> audioPlayerManager, MirroringAudioTrackResolver mirroringAudioTrackResolver) {
		super(audioPlayerManager, mirroringAudioTrackResolver);

		this.tokenTracker = new SpotifyTokenTracker(this, clientId, clientSecret, spDc, customTokenEndpoint);

		if (countryCode == null || countryCode.isEmpty()) {
			countryCode = "US";
		}
		this.countryCode = countryCode;
		this.preferAnonymousToken = preferAnonymousToken;
	}

	public void setPlaylistPageLimit(int playlistPageLimit) {
		this.playlistPageLimit = playlistPageLimit;
	}

	public void setAlbumPageLimit(int albumPageLimit) {
		this.albumPageLimit = albumPageLimit;
	}

	public void setLocalFiles(boolean localFiles) {
		this.localFiles = localFiles;
	}

	public void setResolveArtistsInSearch(boolean resolveArtistsInSearch) {
		// no-op: not applicable with Partner API
	}

	public void setClientIDSecret(String clientId, String clientSecret) {
		this.tokenTracker.setClientIDS(clientId, clientSecret);
	}

	public void setSpDc(String spDc) {
		this.tokenTracker.setSpDc(spDc);
	}

	public void setPreferAnonymousToken(boolean preferAnonymousToken) {
		this.preferAnonymousToken = preferAnonymousToken;
	}

	public void setCustomTokenEndpoint(String customTokenEndpoint) {
		this.tokenTracker.setCustomTokenEndpoint(customTokenEndpoint);
	}

	@NotNull
	@Override
	public String getSourceName() {
		return "spotify";
	}

	@Override
	@Nullable
	public AudioLyrics loadLyrics(@NotNull AudioTrack audioTrack) {
		var spotifyTackId = "";
		if (audioTrack instanceof SpotifyAudioTrack) {
			spotifyTackId = audioTrack.getIdentifier();
		}

		if (spotifyTackId.isEmpty()) {
			AudioItem item = AudioReference.NO_TRACK;
			try {
				if (audioTrack.getInfo().isrc != null && !audioTrack.getInfo().isrc.isEmpty()) {
					item = this.getSearch("isrc:" + audioTrack.getInfo().isrc, false);
				}
				if (item == AudioReference.NO_TRACK) {
					item = this.getSearch(String.format("%s %s", audioTrack.getInfo().title, audioTrack.getInfo().author), false);
				}
			} catch (IOException e) {
				throw new RuntimeException(e);
			}

			if (item == AudioReference.NO_TRACK) {
				return null;
			}
			if (item instanceof AudioTrack) {
				spotifyTackId = ((AudioTrack) item).getIdentifier();
			} else if (item instanceof AudioPlaylist) {
				var playlist = (AudioPlaylist) item;
				if (!playlist.getTracks().isEmpty()) {
					spotifyTackId = playlist.getTracks().get(0).getIdentifier();
				}
			}
		}

		try {
			return this.getLyrics(spotifyTackId);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public AudioLyrics getLyrics(String id) throws IOException {
		if (!this.tokenTracker.hasValidAccountCredentials()) {
			throw new IllegalArgumentException("Spotify spDc must be set");
		}

		var request = new HttpGet(CLIENT_API_BASE + "color-lyrics/v2/track/" + id + "?format=json&vocalRemoval=false");
		request.setHeader("User-Agent", USER_AGENT);
		request.setHeader("App-Platform", "WebPlayer");
		request.setHeader("Authorization", "Bearer " + this.tokenTracker.getAccountAccessToken());
		var json = LavaSrcTools.fetchResponseAsJson(this.httpInterfaceManager.getInterface(), request);
		if (json == null) {
			return null;
		}

		var lyrics = new ArrayList<AudioLyrics.Line>();
		for (var line : json.get("lyrics").get("lines").values()) {
			lyrics.add(new BasicAudioLyrics.BasicLine(
				Duration.ofMillis(line.get("startTimeMs").asLong(0)),
				null,
				line.get("words").text()
			));
		}

		return new BasicAudioLyrics("spotify", json.get("lyrics").get("providerDisplayName").textOrDefault("MusixMatch"), null, lyrics);
	}

	@Override
	public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) throws IOException {
		var extendedAudioTrackInfo = super.decodeTrack(input);
		return new SpotifyAudioTrack(trackInfo,
			extendedAudioTrackInfo.albumName,
			extendedAudioTrackInfo.albumUrl,
			extendedAudioTrackInfo.artistUrl,
			extendedAudioTrackInfo.artistArtworkUrl,
			extendedAudioTrackInfo.previewUrl,
			extendedAudioTrackInfo.isPreview,
			this
		);
	}

	@Override
	@Nullable
	public AudioSearchResult loadSearch(@NotNull String query, @NotNull Set<AudioSearchResult.Type> types) {
		try {
			if (query.startsWith(SEARCH_PREFIX)) {
				return this.getAutocomplete(query.substring(SEARCH_PREFIX.length()), types);
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return null;
	}

	@Override
	public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
		var identifier = reference.identifier;
		var preview = reference.identifier.startsWith(PREVIEW_PREFIX);
		return this.loadItem(preview ? identifier.substring(PREVIEW_PREFIX.length()) : identifier, preview);
	}

	public AudioItem loadItem(String identifier, boolean preview) {
		try {
			if (identifier.startsWith(SEARCH_PREFIX)) {
				return this.getSearch(identifier.substring(SEARCH_PREFIX.length()).trim(), preview);
			}

			if (identifier.startsWith(RECOMMENDATIONS_PREFIX)) {
				return this.getRecommendations(identifier.substring(RECOMMENDATIONS_PREFIX.length()).trim(), preview);
			}

			// If the identifier is a share URL, we need to follow the redirect to find out the real url behind it
			if (identifier.startsWith(SHARE_URL)) {
				var request = new HttpHead(identifier);
				request.setConfig(RequestConfig.custom().setRedirectsEnabled(false).build());
				try (var response = this.httpInterfaceManager.getInterface().execute(request)) {
					if (response.getStatusLine().getStatusCode() == 307) {
						var location = response.getFirstHeader("Location").getValue();
						if (location.startsWith("https://open.spotify.com/")) {
							return this.loadItem(location, preview);
						}
					}
					return null;
				}
			}

			var matcher = URL_PATTERN.matcher(identifier);
			if (!matcher.find()) {
				return null;
			}

			var id = matcher.group("identifier");
			switch (matcher.group("type")) {
				case "album":
					return this.getAlbum(id, preview);

				case "track":
					return this.getTrack(id, preview);

				case "playlist":
					return this.getPlaylist(id, preview);

				case "artist":
					return this.getArtist(id, preview);
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return null;
	}

	public JsonBrowser getJson(String uri, boolean anonymous, boolean preferAnonymous) throws IOException {
		var request = new HttpGet(uri);
		var accessToken = anonymous ? this.tokenTracker.getAnonymousAccessToken() : this.tokenTracker.getAccessToken(preferAnonymous);
		request.addHeader("Authorization", "Bearer " + accessToken);
		return LavaSrcTools.fetchResponseAsJson(this.httpInterfaceManager.getInterface(), request);
	}

	private AudioSearchResult getAutocomplete(String query, Set<AudioSearchResult.Type> types) throws IOException {
		if (types.isEmpty()) {
			types = SEARCH_TYPES;
		}
		var variables = "{\"searchTerm\":\"" + query.replace("\"", "\\\"") + "\",\"offset\":0,\"limit\":10,\"numberOfTopResults\":5,\"includeAudiobooks\":false,\"includeArtistHasConcertsField\":false,\"includePreReleases\":false}";
		var json = this.postPartnerApi("searchDesktop", variables, HASH_SEARCH_DESKTOP);
		if (json == null) {
			return AudioSearchResult.EMPTY;
		}

		var searchData = json.get("data").get("searchV2");

		var albums = new ArrayList<AudioPlaylist>();
		if (types.contains(AudioSearchResult.Type.ALBUM)) {
			for (var album : searchData.get("albumsV2").get("items").values()) {
				var data = album.get("data");
				albums.add(new SpotifyAudioPlaylist(
					data.get("name").safeText(),
					Collections.emptyList(),
					ExtendedAudioPlaylist.Type.ALBUM,
					uriToUrl(data.get("uri").text()),
					data.get("coverArt").get("sources").index(0).get("url").text(),
					data.get("artists").get("items").index(0).get("profile").get("name").text(),
					null
				));
			}
		}

		var artists = new ArrayList<AudioPlaylist>();
		if (types.contains(AudioSearchResult.Type.ARTIST)) {
			for (var artist : searchData.get("artists").get("items").values()) {
				var data = artist.get("data");
				artists.add(new SpotifyAudioPlaylist(
					data.get("profile").get("name").safeText() + "'s Top Tracks",
					Collections.emptyList(),
					ExtendedAudioPlaylist.Type.ARTIST,
					uriToUrl(data.get("uri").text()),
					data.get("visuals").get("avatarImage").get("sources").index(0).get("url").text(),
					data.get("profile").get("name").text(),
					null
				));
			}
		}

		var playlists = new ArrayList<AudioPlaylist>();
		if (types.contains(AudioSearchResult.Type.PLAYLIST)) {
			for (var playlist : searchData.get("playlists").get("items").values()) {
				var data = playlist.get("data");
				playlists.add(new SpotifyAudioPlaylist(
					data.get("name").safeText(),
					Collections.emptyList(),
					ExtendedAudioPlaylist.Type.PLAYLIST,
					uriToUrl(data.get("uri").text()),
					data.get("images").get("items").index(0).get("sources").index(0).get("url").text(),
					data.get("ownerV2").get("data").get("name").text(),
					null
				));
			}
		}

		var tracks = new ArrayList<AudioTrack>();
		if (types.contains(AudioSearchResult.Type.TRACK)) {
			for (var item : searchData.get("tracksV2").get("items").values()) {
				var track = this.parseTrack(item.get("item").get("data"), false);
				if (track != null) {
					tracks.add(track);
				}
			}
		}

		return new BasicAudioSearchResult(tracks, albums, artists, playlists, new ArrayList<>());
	}

	public AudioItem getSearch(String query, boolean preview) throws IOException {
		var variables = "{\"searchTerm\":\"" + query.replace("\"", "\\\"") + "\",\"offset\":0,\"limit\":10,\"numberOfTopResults\":5,\"includeAudiobooks\":false,\"includeArtistHasConcertsField\":false,\"includePreReleases\":false}";
		var json = this.postPartnerApi("searchDesktop", variables, HASH_SEARCH_DESKTOP);
		if (json == null) {
			return AudioReference.NO_TRACK;
		}

		var items = json.get("data").get("searchV2").get("tracksV2").get("items");
		if (items.values().isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		var tracks = new ArrayList<AudioTrack>();
		for (var item : items.values()) {
			var track = this.parseTrack(item.get("item").get("data"), preview);
			if (track != null) {
				tracks.add(track);
			}
		}

		return new BasicAudioPlaylist("Spotify Search: " + query, tracks, null, true);
	}

	public AudioItem getRecommendations(String query, boolean preview) throws IOException {
		Matcher matcher = RADIO_MIX_QUERY_PATTERN.matcher(query);
		if (matcher.find()) {
			String seedType = matcher.group("seedType");
			String seed = matcher.group("seed");
			if (seedType.equals("isrc")) {
				AudioItem item = this.getSearch("isrc:" + seed, preview);
				if (item == AudioReference.NO_TRACK) {
					return AudioReference.NO_TRACK;
				}
				if (item instanceof AudioTrack) {
					seed = ((AudioTrack) item).getIdentifier();
					seedType = "track";
				} else if (item instanceof AudioPlaylist) {
					var playlist = (AudioPlaylist) item;
					if (!playlist.getTracks().isEmpty()) {
						seed = playlist.getTracks().get(0).getIdentifier();
						seedType = "track";
					} else {
						return AudioReference.NO_TRACK;
					}
				}
			}
			JsonBrowser rjson = this.getJson(CLIENT_API_BASE + "inspiredby-mix/v2/seed_to_playlist/spotify:" + seedType + ":" + seed + "?response-format=json", true, this.preferAnonymousToken);
			JsonBrowser mediaItems = rjson.get("mediaItems");
			if (mediaItems.isList() && !mediaItems.values().isEmpty()) {
				String playlistId = mediaItems.index(0).get("uri").text().split(":")[2];
				return this.getPlaylist(playlistId, preview);
			}
		}
		// REST API recommendations endpoint has no Partner API equivalent
		return AudioReference.NO_TRACK;
	}

	public AudioItem getAlbum(String id, boolean preview) throws IOException {
		var tracks = new ArrayList<AudioTrack>();
		var offset = 0;
		var pages = 0;
		String albumName = null;
		String albumUrl = null;
		String albumArtwork = null;
		String artistName = null;
		String artistArtwork = null;
		String artistUrl = null;
		int totalTracks = 0;

		do {
			var variables = "{\"uri\":\"spotify:album:" + id + "\",\"locale\":\"" + this.countryCode + "\",\"offset\":" + offset + ",\"limit\":" + ALBUM_MAX_PAGE_ITEMS + "}";
			var json = this.postPartnerApi("getAlbum", variables, HASH_GET_ALBUM);
			if (json == null) {
				return AudioReference.NO_TRACK;
			}

			var albumUnion = json.get("data").get("albumUnion");
			if ("NotFound".equals(albumUnion.get("__typename").text())) {
				return AudioReference.NO_TRACK;
			}

			if (albumName == null) {
				albumName = albumUnion.get("name").safeText();
				albumArtwork = albumUnion.get("coverArt").get("sources").index(0).get("url").text();
				albumUrl = uriToUrl(albumUnion.get("uri").text());
				var artist = albumUnion.get("artists").get("items").index(0);
				artistName = artist.get("profile").get("name").text();
				artistArtwork = artist.get("visuals").get("avatarImage").get("sources").index(0).get("url").text();
				artistUrl = uriToUrl(artist.get("uri").text());
				totalTracks = (int) albumUnion.get("tracksV2").get("totalCount").asLong(0);
			}

			for (var item : albumUnion.get("tracksV2").get("items").values()) {
				var trackData = item.get("track");
				var trackId = extractIdFromUri(trackData.get("uri").text());
				var trackArtistName = trackData.get("artists").get("items").index(0).get("profile").get("name").safeText();

				tracks.add(new SpotifyAudioTrack(
					new AudioTrackInfo(
						trackData.get("name").safeText(),
						trackArtistName.isEmpty() ? "Unknown" : trackArtistName,
						preview ? PREVIEW_LENGTH : trackData.get("duration").get("totalMilliseconds").asLong(0),
						trackId != null ? trackId : "local",
						false,
						uriToUrl(trackData.get("uri").text()),
						albumArtwork,
						null
					),
					albumName,
					albumUrl,
					artistUrl,
					artistArtwork,
					null,
					preview,
					this
				));
			}

			offset += ALBUM_MAX_PAGE_ITEMS;
		}
		while (offset < totalTracks && ++pages < this.albumPageLimit);

		if (tracks.isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		return new SpotifyAudioPlaylist(albumName, tracks, ExtendedAudioPlaylist.Type.ALBUM, albumUrl, albumArtwork, artistName, totalTracks);
	}

	public AudioItem getPlaylist(String id, boolean preview) throws IOException {
		var tracks = new ArrayList<AudioTrack>();
		var offset = 0;
		var pages = 0;
		String playlistName = null;
		String playlistUrl = null;
		String playlistArtwork = null;
		String ownerName = null;
		int totalTracks = 0;

		do {
			var variables = "{\"uri\":\"spotify:playlist:" + id + "\",\"offset\":" + offset + ",\"limit\":" + PLAYLIST_MAX_PAGE_ITEMS + ",\"enableWatchFeedEntrypoint\":false}";
			var json = this.postPartnerApi("fetchPlaylist", variables, HASH_FETCH_PLAYLIST);
			if (json == null) {
				return AudioReference.NO_TRACK;
			}

			var playlistV2 = json.get("data").get("playlistV2");
			if ("NotFound".equals(playlistV2.get("__typename").text())) {
				return AudioReference.NO_TRACK;
			}

			if (playlistName == null) {
				playlistName = playlistV2.get("name").safeText();
				playlistArtwork = playlistV2.get("images").get("items").index(0).get("sources").index(0).get("url").text();
				ownerName = playlistV2.get("ownerV2").get("data").get("name").text();
				playlistUrl = uriToUrl(playlistV2.get("uri").text());
				totalTracks = (int) playlistV2.get("content").get("totalCount").asLong(0);
			}

			for (var item : playlistV2.get("content").get("items").values()) {
				var itemData = item.get("itemV2").get("data");
				var typeName = itemData.get("__typename").text();
				if (typeName == null || !typeName.equals("TrackResponseWrapper")) {
					continue;
				}
				var uri = itemData.get("uri").text();
				if (uri == null) {
					if (!this.localFiles) {
						continue;
					}
				}

				var track = this.parseTrack(itemData, preview);
				if (track != null) {
					tracks.add(track);
				}
			}

			offset += PLAYLIST_MAX_PAGE_ITEMS;
		}
		while (offset < totalTracks && ++pages < this.playlistPageLimit);

		return new SpotifyAudioPlaylist(playlistName, tracks, ExtendedAudioPlaylist.Type.PLAYLIST, playlistUrl, playlistArtwork, ownerName, totalTracks);
	}

	public AudioItem getArtist(String id, boolean preview) throws IOException {
		var variables = "{\"uri\":\"spotify:artist:" + id + "\",\"locale\":\"" + this.countryCode + "\",\"includePrerelease\":false}";
		var json = this.postPartnerApi("queryArtistOverview", variables, HASH_QUERY_ARTIST_OVERVIEW);
		if (json == null) {
			return AudioReference.NO_TRACK;
		}

		var artistUnion = json.get("data").get("artistUnion");
		if ("NotFound".equals(artistUnion.get("__typename").text())) {
			return AudioReference.NO_TRACK;
		}

		var artistName = artistUnion.get("profile").get("name").safeText();
		var artistArtwork = artistUnion.get("visuals").get("avatarImage").get("sources").index(0).get("url").text();
		var artistUrl = uriToUrl(artistUnion.get("uri").text());

		var topTracks = artistUnion.get("discography").get("topTracks").get("items");
		if (topTracks.values().isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		var tracks = new ArrayList<AudioTrack>();
		for (var item : topTracks.values()) {
			var trackData = item.get("track");
			var trackId = extractIdFromUri(trackData.get("uri").text());
			var trackArtistName = trackData.get("artists").get("items").index(0).get("profile").get("name").safeText();
			var albumData = trackData.get("albumOfTrack");

			tracks.add(new SpotifyAudioTrack(
				new AudioTrackInfo(
					trackData.get("name").safeText(),
					trackArtistName.isEmpty() ? "Unknown" : trackArtistName,
					preview ? PREVIEW_LENGTH : trackData.get("duration").get("totalMilliseconds").asLong(0),
					trackId != null ? trackId : "local",
					false,
					uriToUrl(trackData.get("uri").text()),
					albumData.get("coverArt").get("sources").index(0).get("url").text(),
					null
				),
				albumData.get("name").text(),
				uriToUrl(albumData.get("uri").text()),
				artistUrl,
				artistArtwork,
				null,
				preview,
				this
			));
		}

		return new SpotifyAudioPlaylist(artistName + "'s Top Tracks", tracks, ExtendedAudioPlaylist.Type.ARTIST, artistUrl, artistArtwork, artistName, tracks.size());
	}

	public AudioItem getTrack(String id, boolean preview) throws IOException {
		var variables = "{\"uri\":\"spotify:track:" + id + "\"}";
		var json = this.postPartnerApi("getTrack", variables, HASH_GET_TRACK);
		if (json == null) {
			return AudioReference.NO_TRACK;
		}

		var trackUnion = json.get("data").get("trackUnion");
		if ("NotFound".equals(trackUnion.get("__typename").text())) {
			return AudioReference.NO_TRACK;
		}

		var trackId = extractIdFromUri(trackUnion.get("uri").text());
		var artist = trackUnion.get("firstArtist").get("items").index(0);
		var artistName = artist.get("profile").get("name").safeText();
		var artistArtwork = artist.get("visuals").get("avatarImage").get("sources").index(0).get("url").text();
		var artistUri = artist.get("uri").text();
		var artistUrl = artistUri != null ? "https://open.spotify.com/artist/" + extractIdFromUri(artistUri) : null;
		var albumData = trackUnion.get("albumOfTrack");
		var duration = trackUnion.get("duration").get("totalMilliseconds").asLong(0);
		if (duration == 0) {
			duration = trackUnion.get("trackDuration").get("totalMilliseconds").asLong(0);
		}

		return new SpotifyAudioTrack(
			new AudioTrackInfo(
				trackUnion.get("name").safeText(),
				artistName.isEmpty() ? "Unknown" : artistName,
				preview ? PREVIEW_LENGTH : duration,
				trackId != null ? trackId : "local",
				false,
				uriToUrl(trackUnion.get("uri").text()),
				albumData.get("coverArt").get("sources").index(0).get("url").text(),
				trackUnion.get("externalIds").get("isrc").text()
			),
			albumData.get("name").text(),
			uriToUrl(albumData.get("uri").text()),
			artistUrl,
			artistArtwork,
			null,
			preview,
			this
		);
	}

	private AudioTrack parseTrack(JsonBrowser trackData, boolean preview) {
		if (trackData.get("uri").text() == null) {
			return null;
		}

		var trackId = extractIdFromUri(trackData.get("uri").text());
		var artist = trackData.get("artists").get("items").index(0);
		var artistName = artist.get("profile").get("name").safeText();
		var albumData = trackData.get("albumOfTrack");

		return new SpotifyAudioTrack(
			new AudioTrackInfo(
				trackData.get("name").safeText(),
				artistName.isEmpty() ? "Unknown" : artistName,
				preview ? PREVIEW_LENGTH : trackData.get("duration").get("totalMilliseconds").asLong(0),
				trackId != null ? trackId : "local",
				false,
				uriToUrl(trackData.get("uri").text()),
				albumData.get("coverArt").get("sources").index(0).get("url").text(),
				trackData.get("externalIds").get("isrc").text()
			),
			albumData.get("name").text(),
			uriToUrl(albumData.get("uri").text()),
			uriToUrl(artist.get("uri").text()),
			artist.get("visuals").get("avatarImage").get("sources").index(0).get("url").text(),
			null,
			preview,
			this
		);
	}

	private static String extractIdFromUri(String uri) {
		if (uri == null) return null;
		var parts = uri.split(":");
		return parts.length >= 3 ? parts[2] : uri;
	}

	private static String uriToUrl(String uri) {
		if (uri == null) return null;
		var parts = uri.split(":");
		if (parts.length >= 3) {
			return "https://open.spotify.com/" + parts[1] + "/" + parts[2];
		}
		return null;
	}

	private JsonBrowser postPartnerApi(String operationName, String variables, String hash) throws IOException {
		var accessToken = this.tokenTracker.getAccessToken(true);
		var request = new HttpPost(PARTNER_API_BASE);
		request.addHeader("Authorization", "Bearer " + accessToken);
		request.addHeader("App-Platform", "WebPlayer");
		request.addHeader("Spotify-App-Version", "1.2.81.104.g225ec0e6");
		var body = "{\"variables\":" + variables
			+ ",\"operationName\":\"" + operationName + "\""
			+ ",\"extensions\":{\"persistedQuery\":{\"version\":1,\"sha256Hash\":\"" + hash + "\"}}}";
		request.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
		return LavaSrcTools.fetchResponseAsJson(this.httpInterfaceManager.getInterface(), request);
	}

	@Override
	public void shutdown() {
		try {
			this.httpInterfaceManager.close();
		} catch (IOException e) {
			log.error("Failed to close HTTP interface manager", e);
		}
	}

	@Override
	public void configureRequests(Function<RequestConfig, RequestConfig> configurator) {
		this.httpInterfaceManager.configureRequests(configurator);
	}

	@Override
	public void configureBuilder(Consumer<HttpClientBuilder> configurator) {
		this.httpInterfaceManager.configureBuilder(configurator);
	}
}