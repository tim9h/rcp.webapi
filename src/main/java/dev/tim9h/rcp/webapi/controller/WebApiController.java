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
import dev.tim9h.rcp.webapi.WebApiViewFactory;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import javafx.application.Platform;

@Singleton
public class WebApiController {

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

		var port = settings.getInt(WebApiViewFactory.SETTING_PORT);
		if (port == null) {
			logger.error("Api controller settings are not properly configured");
			em.echo("Api controller settings are not properly configured");
			return;
		}

		thread = new Thread(() -> {
			server = Javalin
					.create(config -> config.router.mount(router -> router.beforeMatched(authManager::handleAccess)))
					.start(port);

			logger.info(() -> "Api controller started on port " + port);
			em.echo("Api controller started");

			createPostMapping(LOGILED, "color", this::setLogiledColor);
			createGetMapping(LOGILED, this::returnLogiledStatus);

			createPostMapping("next", () -> em.post("next"));
			createPostMapping("previous", () -> em.post("previous"));
			createPostMapping("play", () -> em.post("play"));
			createPostMapping("pause", () -> em.post("pause"));
			createPostMapping("stop", () -> em.post("stop"));
			createPostMapping("volumeup", () -> em.post("volumeup"));
			createPostMapping("volumedown", () -> em.post("volumedown"));
			createPostMapping("mute", () -> em.post("mute"));

			createPostMapping("lock", () -> em.post("lock"));
			createPostMapping("shutdown", "time", time -> em.post("shutdown", time));

			createGetMapping("np", this::returnCurrentTrack);

		}, "WebApiController");
		thread.setDaemon(true);
		thread.start();
	}

	private void createPostMapping(String path, Runnable runnable) {
		createPostMapping(path, "", _ -> runnable.run(), null);
	}

	private void createPostMapping(String path, String param, Consumer<String> consumer) {
		createPostMapping(path, param, consumer, null);
	}

	private void createPostMapping(String path, String param, Consumer<String> consumer, Consumer<Context> response) {
		server.post(path, ctx -> {
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
		logger.info(() -> "Post mapping created: " + path);
	}

	private void createGetMapping(String path, Consumer<Context> response) {
		server.get(path, response::accept, OPERATOR);
		logger.info(() -> "Get mapping created: " + path);
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
