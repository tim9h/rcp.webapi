package dev.tim9h.rcp.webapi.controller;

import org.apache.logging.log4j.Logger;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import dev.tim9h.rcp.logging.InjectLogger;
import dev.tim9h.rcp.service.CryptoService;
import dev.tim9h.rcp.settings.Settings;
import dev.tim9h.rcp.webapi.WebApiViewFactory;
import io.javalin.http.Context;
import io.javalin.http.UnauthorizedResponse;
import io.javalin.security.RouteRole;

@Singleton
public class AuthManager {

	private static final String IPV6_LOCALHOST = "[0:0:0:0:0:0:0:1]";

	private Settings settings;

	private CryptoService cryptoService;

	@InjectLogger
	private Logger logger;

	public enum Role implements RouteRole {
		ANYONE, OPERATOR
	}

	@Inject
	public AuthManager(Settings settings, CryptoService cryptoService) {
		this.settings = settings;
		this.cryptoService = cryptoService;
	}

	public void handleAccess(Context ctx) {
		if (ctx.routeRoles().contains(Role.ANYONE)) {
			return;
		}
		if (apiKeyValid(ctx) && ipAllowed(ctx)) {
			return;
		}
		throw new UnauthorizedResponse();
	}

	private boolean apiKeyValid(Context ctx) {
		var apiKey = ctx.header("X-API-Key");
		var storedHash = settings.getString(WebApiViewFactory.SETTING_APIKEY);
		return cryptoService.hashMatches(apiKey, storedHash);
	}

	private boolean ipAllowed(Context ctx) {
		if (ctx.host().startsWith("localhost")) {
			logger.debug(() -> "Request from localhost, allowing access");
			return true;
		}
		var allowedIps = settings.getStringList(WebApiViewFactory.SETTING_ALLOWEDIPS);
		var ip = ctx.ip();
		var allowed = allowedIps.contains(ip) || IPV6_LOCALHOST.equals(ip);
		if (!allowed) {
			logger.info(() -> "Access attempt from disallowed IP: " + ip + " from host " + ctx.host());
		}
		return allowed;
	}

}
