package dev.tim9h.rcp.webapi;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;

import com.google.inject.Inject;

import dev.tim9h.rcp.event.EventManager;
import dev.tim9h.rcp.logging.InjectLogger;
import dev.tim9h.rcp.service.CryptoService;
import dev.tim9h.rcp.settings.Settings;
import dev.tim9h.rcp.spi.CCard;
import dev.tim9h.rcp.spi.Mode;
import dev.tim9h.rcp.spi.StringNode;
import dev.tim9h.rcp.spi.TreeNode;
import dev.tim9h.rcp.webapi.controller.WebApiController;

public class WebApiView implements CCard {
	
	@InjectLogger
	private Logger logger;

	@Inject
	private EventManager eventManager;
	
	@Inject
	private CryptoService cryptoService;
	
	@Inject
	private Settings settings;

	@Override
	public String getName() {
		return "webapi";
	}
	
	@Inject
	private WebApiController controller;
	
	@Override
	public Optional<List<Mode>> getModes() {
		return Optional.of(Arrays.asList(new Mode() {
			
			@Override
			public void onEnable() {
				eventManager.showWaitingIndicatorAsync();
				controller.start();
			}
			
			@Override
			public void onDisable() {
				eventManager.showWaitingIndicatorAsync();
				controller.stop();
			}
			
			@Override
			public String getName() {
				return "api";
			}
		}));
	}
	
	@Override
	public Optional<TreeNode<String>> getModelessCommands() {
		var password = new StringNode();
		password.add("api").add("genapikey");
		return Optional.of(password);
	}
	
	@Override
	public void initBus(EventManager eventManager) {
		CCard.super.initBus(eventManager);
		eventManager.listen("api", data -> {
			if (data == null) {
				return;
			}
			if ("genapikey".equals(data[0])) {
				generateApiKey();
			}
		});
	}

	private void generateApiKey() {
		var apiKey = cryptoService.gernateApiKey();
		var hash = cryptoService.hashSha256(apiKey);
		settings.persist(WebApiViewFactory.SETTING_APIKEY, hash);
		logger.info(() -> "New API key generated");
		copyToClipboard(apiKey);
		eventManager.echo(apiKey, "New API key generated and copied to clipboard");
	}
	
	private void copyToClipboard(String apiKey) {
		if (StringUtils.isNotBlank(apiKey)) {
			logger.debug(() -> "API key copied to clipboard");
			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(apiKey), null);
		}
	}

}
