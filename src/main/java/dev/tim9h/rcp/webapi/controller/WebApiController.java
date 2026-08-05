package dev.tim9h.rcp.webapi.controller;

import static dev.tim9h.rcp.webapi.controller.AuthManager.Role.OPERATOR;

import java.util.function.Consumer;

import org.apache.logging.log4j.Logger;

import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Singleton;

import dev.tim9h.rcp.event.EventManager;
import dev.tim9h.rcp.logging.InjectLogger;
import dev.tim9h.rcp.settings.Settings;
import dev.tim9h.rcp.webapi.WebApiView;
import io.javalin.Javalin;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import javafx.application.Platform;

@Singleton
public class WebApiController {

	private static final int RESPONSE_TIMEOUT_MS = 5000;

	private static final String LOGILED = "logiled";

	@InjectLogger
	private Logger logger;

	@Inject
	private Settings settings;

	@Inject
	private EventManager em;

	private Javalin server;

	private Thread thread;

	private String title;

	private String artist;

	private String album;

	private boolean isPlaying;

	public record Track(String title, String artist, String album, boolean isPlaying) {
	}

	public record LogiledStatus(boolean enabled, String color) {
	}

	@Inject
	private AuthManager authManager;

	@Inject
	public WebApiController(Injector injector) {
		injector.injectMembers(this);
		subscribeToNp();
	}

	public void start() {
		logger.info(() -> "Starting Api Controller");

		var port = settings.getInt(WebApiView.SETTING_PORT);
		if (port == null) {
			logger.error("Api controller settings are not properly configured");
			em.echo("Api controller settings are not properly configured");
			return;
		}

		thread = new Thread(() -> {
			server = Javalin.create(config -> {
				createGetMapping(config.routes, LOGILED, this::returnLogiledStatus);
				createPostMapping(config.routes, LOGILED, "color", this::setLogiledColor);
				// Media commands with response tracking
				createPostMappingWithResponse(config.routes, "next", "", "next");
				createPostMappingWithResponse(config.routes, "previous", "", "previous");
				createPostMappingWithResponse(config.routes, "play", "", "play");
				createPostMappingWithResponse(config.routes, "pause", "", "pause");
				createPostMappingWithResponse(config.routes, "stop", "", "stop");
				createPostMappingWithResponse(config.routes, "volumeup", "", "volumeup");
				createPostMappingWithResponse(config.routes, "volumedown", "", "volumedown");
				createPostMappingWithResponse(config.routes, "mute", "", "mute");
				createPostMapping(config.routes, "lock", "", _ -> em.post("lock"));
				createPostMapping(config.routes, "shutdown", "time", time -> em.post("shutdown", time));
				createPostMapping(config.routes, "toast", "message", message -> em.showToast(message));
				createGetMapping(config.routes, "np", this::returnCurrentTrack);

				config.routes.beforeMatched(authManager::handleAccess);
			}).start(port);
			logger.info(() -> "Api controller started on port " + port);
			em.echo("Api controller started");
		}, "WebApiController");
		thread.setDaemon(true);
		thread.start();
	}

	private void createGetMapping(RoutesConfig routes, String path, Consumer<Context> response) {
		routes.get(path, response::accept, OPERATOR);
	}

	private void createPostMapping(RoutesConfig routes, String path, String param, Consumer<String> consumer) {
		createPostMapping(routes, path, param, consumer, null);
	}

	private void createPostMapping(RoutesConfig routes, String path, String param, Consumer<String> consumer,
			Consumer<Context> response) {
		routes.post(path, ctx -> {
			try {
				var value = ctx.queryParam(param);
				logger.debug(() -> String.format("Handling post request for %s%s", path,
						param.equals("") ? "" : " (" + param + ": " + value + ")"));
				Platform.runLater(() -> consumer.accept(value));
				if (response != null) {
					response.accept(ctx);
				}
			} catch (IllegalArgumentException _) {
				logger.warn(() -> String.format("Path parameter %s for post mapping %s not found", param, path));
			}
		}, OPERATOR);
	}

	private void createPostMappingWithResponse(RoutesConfig routes, String path, String param, String eventName) {
		routes.post(path, ctx -> {
			try {
				var value = ctx.queryParam(param);
				logger.debug(() -> String.format("Handling post request for %s (with response tracking)%s", path,
						param.equals("") ? "" : " (" + param + ": " + value + ")"));

				// Generate a correlation ID for this request
				var correlationId = "webapi-" + System.nanoTime();

				// Post the request with correlation ID and wait for response
				em.postRequest(eventName, correlationId, value != null ? value : "");

				// Wait for response with 5-second timeout
				var response = em.listenForResponse(correlationId, RESPONSE_TIMEOUT_MS);

				if (response != null && response.length > 0) {
					var status = (String) response[0];
					if ("success".equals(status)) {
						ctx.status(HttpStatus.OK);
						// Build response with track info for media commands
						var result = new java.util.HashMap<String, Object>();
						result.put("status", status);
						result.put("command", eventName);

						// For media commands (next, previous, play, pause, stop), include track info
						if (response.length >= 5 && ("next".equals(eventName) || "previous".equals(eventName)
								|| eventName.equals("play") || eventName.equals("pause") || eventName.equals("stop"))) {
							var title = response[1] != null ? response[1].toString() : "";
							var artist = response[2] != null ? response[2].toString() : "";
							var album = response[3] != null ? response[3].toString() : "";
							var isPlaying = response[4] instanceof Boolean ? (Boolean) response[4] : false;

							result.put("track", new java.util.HashMap<String, Object>() {
								{
									put("title", title);
									put("artist", artist);
									put("album", album);
									put("isPlaying", isPlaying);
								}
							});
							result.put("message", eventName + " completed");
							logger.debug(
									() -> "Response sent for " + eventName + " with track: " + title + " - " + artist);
						} else if (response.length > 1 && "volumeup".equals(eventName)
								|| "volumedown".equals(eventName)) {
							// For volume commands, include volume level
							result.put("volume", response[1]);
							result.put("message", eventName + " completed successfully");
							logger.debug(() -> "Response sent for " + eventName + ": new volume level " + response[1]);
						} else if (response.length > 1) {
							// For other commands, include raw response details
							result.put("details", java.util.Arrays.copyOfRange(response, 1, response.length));
							result.put("message", eventName + " completed successfully");

						} else {
							result.put("message", eventName + " completed successfully");
							logger.debug(() -> "Response sent for " + eventName + ": success");
						}
						ctx.json(result);
					} else if ("error".equals(status)) {
						ctx.status(HttpStatus.INTERNAL_SERVER_ERROR);
						var errorMsg = response.length > 1 ? response[1].toString() : "Unknown error";
						ctx.result("{\"status\":\"error\",\"command\":\"" + eventName + "\",\"message\":\"" + errorMsg
								+ "\"}");
						logger.warn(() -> "Error response for " + eventName + ": " + errorMsg);
					} else {
						ctx.status(HttpStatus.INTERNAL_SERVER_ERROR);
						ctx.result("{\"status\":\"unknown\",\"command\":\"" + eventName
								+ "\",\"message\":\"Unexpected response\"}");
					}
				} else {
					// Timeout
					ctx.status(HttpStatus.REQUEST_TIMEOUT);
					ctx.result("{\"status\":\"timeout\",\"command\":\"" + eventName
							+ "\",\"message\":\"No response from media service within 5 seconds\"}");
					logger.warn(() -> "Timeout waiting for response from " + eventName);
				}
			} catch (IllegalArgumentException e) {
				logger.warn(() -> String.format("Path parameter %s for post mapping %s not found", param, path));
				ctx.status(HttpStatus.BAD_REQUEST);
				ctx.result("{\"status\":\"error\",\"message\":\"Missing required parameter: " + param + "\"}");
			} catch (Exception e) {
				logger.error(() -> "Error handling post mapping for " + path, e);
				ctx.status(HttpStatus.INTERNAL_SERVER_ERROR);
				ctx.result("{\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}");
			}
		}, OPERATOR);
	}

	private void setLogiledColor(String color) {
		if ("on".equalsIgnoreCase(color)) {
			em.post(LOGILED);
		} else {
			em.post(LOGILED, color);
		}
	}

	private void returnLogiledStatus(Context ctx) {
		var enabled = settings.getStringSet("core.modes").contains(LOGILED);
		var color = settings.getString("logiled.lighting.color");
		var status = new LogiledStatus(enabled, color);
		ctx.result(Boolean.toString(enabled));
		ctx.json(status);
		ctx.status(HttpStatus.OK);
		logger.debug(() -> "Returning logiled status: " + enabled + ", color: " + color);
	}

	private void subscribeToNp() {
		em.listen("np", currentTrack -> {
			if (currentTrack == null) {
				return;
			}
			this.title = (String) currentTrack[0];
			this.artist = (String) currentTrack[1];
			this.album = (String) currentTrack[2];
			this.isPlaying = (boolean) currentTrack[3];
		});
	}

	private void returnCurrentTrack(Context ctx) {
		em.post("np");
		if (title == null || artist == null || album == null) {
			ctx.status(HttpStatus.NOT_FOUND);
			return;
		}
		var currentTrack = new Track(title, artist, album, isPlaying);
		logger.debug(() -> "Returning current track: " + currentTrack);
		ctx.json(currentTrack);
		ctx.status(HttpStatus.OK);
	}

	public void stop() {
		if (thread != null && server != null) {
			server.stop();
			server = null;
			logger.info(() -> "Stopping api controller");
			em.echo("Api controller stopped");
			thread.interrupt();
			thread = null;
			logger.debug(() -> "Api thread stopped");
		} else {
			em.echo("Api controller not running");
		}
	}

}