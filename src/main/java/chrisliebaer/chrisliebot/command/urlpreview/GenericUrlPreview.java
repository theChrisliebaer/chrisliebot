package chrisliebaer.chrisliebot.command.urlpreview;


import chrisliebaer.chrisliebot.abstraction.ChrislieFormat;
import chrisliebaer.chrisliebot.abstraction.ChrislieOutput;
import chrisliebaer.chrisliebot.command.ChrislieListener;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.net.URL;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
public class GenericUrlPreview implements Callback {
	
	private static final int MAX_IRC_MESSAGE_LENGTH = 700;
	private static final int MAX_CONTENT_LENGTH = 5 * 1024 * 1024;
	private static final long PREVIEW_TIMEOUT = 10000; // cancel connection after 10 seconds even if we are still receiving data
	
	private OkHttpClient client;
	private ScheduledExecutorService timer;
	
	private URL url;
	private ChrislieListener.ListenerMessage m;
	private Set<UrlPreviewListener.HistoryEntry> titleHistory;
	
	@SneakyThrows
	public GenericUrlPreview(@NonNull URL url, ChrislieListener.ListenerMessage m, Set<UrlPreviewListener.HistoryEntry> titleHistory) {
		this.url = url;
		this.m = m;
		this.titleHistory = titleHistory;
		
		var shared = m.bot().sharedResources();
		client = shared.httpClient();
		timer = shared.timer();
	}
	
	public void start() {
		var req = new Request.Builder().get()
				.url(url)
				.header("User-Agent", "Twitterbot/1.0") // otherwise we get blocked too often :(
				.build();
		var call = client.newCall(req);
		call.enqueue(this);
		
		// queue timer for cancelation
		timer.schedule(() -> {
			
			call.cancel();
			if (!call.isExecuted())
				log.debug("canceled preview of {} since it took to long", url);
		}, PREVIEW_TIMEOUT, TimeUnit.MILLISECONDS);
	}
	
	@Override
	public void onFailure(Call call, IOException e) {
		if (!e.getMessage().isEmpty())
			log.debug("failed to connect to {}: {}", url, e.getMessage());
	}
	
	@Override
	public void onResponse(Call call, Response response) throws IOException {
		
		// check for mime type
		String contentType = response.header("Content-Type");
		if (contentType == null) {
			log.debug("no content type provided: {}", url);
			return;
		}
		
		// we only care about html pages
		String mime = contentType.split(";")[0].trim();
		if (!"text/html".equalsIgnoreCase(mime)) {
			log.debug("can't parse content type {} for {}", mime, url);
		}
		
		// documentation doesn't mention it, but we have to close the body
		try (response; ResponseBody cutBody = response.peekBody(MAX_CONTENT_LENGTH)) {
			Document doc = Jsoup.parse(cutBody.string());
			
			// try to get title first
			String summary = doc.title();
			
			// but prefer open graph
			Elements metaOgTitle = doc.select("meta[property=og:title]");
			if (metaOgTitle != null) {
				var ogTitle = metaOgTitle.attr("content");
				summary = ogTitle.isEmpty() ? summary : ogTitle;
			}
			
			// and try to also append open graph description
			Elements metaOgDescription = doc.select("meta[property=og:description]");
			if (metaOgDescription != null) {
				var ogDescription = metaOgDescription.attr("content");
				summary += ogDescription.isEmpty() ? "" : (" - " + ogDescription);
			}
			
			summary = summary
					.replaceAll("[\n\r\u0000]", "") // remove illegal irc characters
					.trim();
			
			// limit output to 500 characters at max
			if (summary.length() > MAX_IRC_MESSAGE_LENGTH)
				summary = summary.substring(0, MAX_IRC_MESSAGE_LENGTH).trim() + "[...]";
			
			// check if summary was posted before within timeout window
			UrlPreviewListener.HistoryEntry historyLookup = new UrlPreviewListener.HistoryEntry(summary, m.msg().channel().identifier());
			if (titleHistory.contains(historyLookup)) {
				// output has been posted, don't repeat
				log.debug("not posting summary of {} in {} since it's identical with a recently posted summary",
						url.toExternalForm(), m.msg().channel().displayName());
				return;
			}
			
			// add output to history
			titleHistory.add(historyLookup);
			
			if (!summary.isEmpty()) {
				try { // TODO: create function in ListenerMessage that can be used to unwrap exceptions and feed back to dispatcher for error handling in async code
					ChrislieOutput reply;
					reply = m.reply();
					reply.plain()
							.appendEscape("Linkvorschau: ", ChrislieFormat.BOLD)
							.appendEscape(summary);
					reply.send();
				} catch (ChrislieListener.ListenerException e) {
					log.warn("failed to create output for link preview", e);
				}
			}
		}
	}
}
