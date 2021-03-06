package chrisliebaer.chrisliebot.command.external;

import chrisliebaer.chrisliebot.command.ChrislieListener;
import lombok.NonNull;

import java.util.Objects;

// HUGE TODO
/*
	Config
	url für aufruf
	flag ob auch listener implementiert wird
	flag ob command implementiert wird
	liste von flex conf werten, die übertragen werden, (kein wildcard, weil nicht möglich und ggf. sicherheitskritisch)
	user definiertes json object in static config
	
	erstmal nur ein betriebsmodi:
		nachricht wird als json im post übertragen, antwort wird als serialized output erwartet
		ggf. auch plain mode, irgendwie, vermutlich nicht
	
	an der stelle überlegen ob zusammenlegen mit websocken api und webhook sinnvoll
	
	output limits werden von limiter durchgesetzt
	
	websocket api:
	normale definition als commandlistener, listener registriert sich selbst im webserver
	api key in der config, wird von webserver instanz verwaltet
	events: listener, command konfigurierbar
	
	websocket client kann beliebige nachrichten schicken, target scope wird berechnet und berechtigung geprüft, sonst nachricht blocken und client trennen
	
	webhook funktioniert ähnlich, kann jedoch nur reagieren und ob er das ziel auswählen kann pendeln wir noch aus
 */
public abstract class ExternalCommandListener implements ChrislieListener.Command {
	
	protected abstract @NonNull @org.checkerframework.checker.nullness.qual.NonNull Config externalConfig();
	
	private Config cfg() {
		return Objects.requireNonNull(externalConfig());
	}
	
	@Override
	public final void execute(Invocation invc) throws ListenerException {
		if (cfg().implementsCommand)
			handleCommand(invc);
	}
	
	@Override
	public final void onMessage(ListenerMessage msg, boolean isCommand) throws ListenerException {
		var cfg = cfg();
		
		if (cfg.implementsListener && (!isCommand) || cfg.includeCommands)
			externalMessage(msg);
	}
	
	protected abstract void handleCommand(Invocation invc) throws ListenerException;
	
	protected abstract void externalMessage(ListenerMessage msg) throws ListenerException;
	
	protected static class Config {
		
		private boolean implementsListener;
		private boolean implementsCommand;
		private boolean includeCommands;
	}
}
