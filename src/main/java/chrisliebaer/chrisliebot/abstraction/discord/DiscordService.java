package chrisliebaer.chrisliebot.abstraction.discord;

import chrisliebaer.chrisliebot.Chrisliebot;
import chrisliebaer.chrisliebot.abstraction.ChrislieChannel;
import chrisliebaer.chrisliebot.abstraction.ChrislieMessage;
import chrisliebaer.chrisliebot.abstraction.ChrislieService;
import chrisliebaer.chrisliebot.abstraction.ServiceAttached;
import chrisliebaer.chrisliebot.command.ChrislieListener;
import chrisliebaer.chrisliebot.config.AliasSet;
import chrisliebaer.chrisliebot.config.ContextResolver;
import chrisliebaer.chrisliebot.config.scope.Selector;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.TextChannel;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.ShutdownEvent;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.interaction.SlashCommandEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.SubscribeEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.apache.commons.lang.StringUtils;

import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Slf4j
public class DiscordService implements ChrislieService {
	
	public static final String PREFIX_GUILD_CHANNEL = "G:";
	public static final String PREFIX_PRIVATE_CHANNEL = "P:";
	
	@Getter private Chrisliebot bot;
	@Getter private JDA jda;
	@Getter private String identifier;
	
	@Setter private Consumer<ChrislieMessage> sink;
	
	private ContextResolver ctxResolver;
	
	// keeps track of which guilds we have already registered our commands
	private final Set<Long> registeredGuilds = new HashSet<>();
	private ScheduledFuture<?> commandUpdater;
	
	@SuppressWarnings("ThisEscapedInObjectConstruction")
	public DiscordService(Chrisliebot bot, JDA jda, String identifier) {
		this.bot = bot;
		this.jda = jda;
		this.identifier = identifier;
		
		jda.addEventListener(this);
	}
	
	@Override
	public void awaitReady() throws InterruptedException {
		jda.awaitReady();
	}
	
	@Override
	public Optional<ChrislieChannel> channel(String identifier) {
		if (identifier.startsWith(PREFIX_GUILD_CHANNEL)) {
			var channel = jda.getGuildChannelById(identifier.substring(PREFIX_GUILD_CHANNEL.length()));
			return channel == null ? Optional.empty() : Optional.of(new DiscordGuildChannel(this, (TextChannel) channel));
		}
		if (identifier.startsWith(PREFIX_PRIVATE_CHANNEL)) {
			var user = jda.getUserById(identifier.substring(PREFIX_PRIVATE_CHANNEL.length()));
			if (user == null)
				return Optional.empty();
			var future = user.openPrivateChannel().submit();
			try {
				var channel = future.get();
				return Optional.of(new DiscordPrivateChannel(this, channel));
			} catch (InterruptedException ignore) {
				Thread.currentThread().interrupt();
				return Optional.empty();
			} catch (ExecutionException | CancellationException ignore) {
				return Optional.empty();
			}
		}
		
		return Optional.empty();
	}
	
	@Override
	public Optional<DiscordUser> user(String identifier) {
		User user = jda.getUserById(identifier);
		return user == null ? Optional.empty() : Optional.of(new DiscordUser(this, user));
	}
	
	@Override
	public Optional<DiscordGuild> guild(String identifier) {
		return Optional.ofNullable(jda.getGuildById(identifier))
				.map(guild -> new DiscordGuild(this, guild));
	}
	
	@SubscribeEvent
	public void onMessage(MessageReceivedEvent ev) {
		if (ev.getAuthor().isBot())
			return;
		
		var sink = this.sink;
		if (sink != null)
			sink.accept(new DiscordMessage(this, ev));
	}
	
	@Override
	public void announceResolver(@NonNull ContextResolver ctxResolver) {
		this.ctxResolver = ctxResolver;
		commandUpdater = bot.sharedResources().timer().scheduleWithFixedDelay(() -> {
			try {
				refreshGuildCommands();
			} catch (Throwable e) {
				log.error("error while updating guild commands", e);
			}
		}, 0, 60, TimeUnit.SECONDS);
	}
	
	private void refreshGuildCommands() {
		// need to wait for bot framework to announce resolver
		if (ctxResolver == null)
			return;
		
		try {
			
			// there is no easy way to check which guilds need to be updated with new commands, so we simply update all
			synchronized (registeredGuilds) {
				var guilds = new ArrayList<>(jda.getGuilds()); // returned list is immutable
				guilds.removeIf(guild -> registeredGuilds.contains(guild.getIdLong()));
				
				for (var guild : guilds) {
					try {
						//registerCommandsOnGuild(guild);
						hotfixRemoveCommandsFromGuild(guild);
						registeredGuilds.add(guild.getIdLong());
					} catch (ExecutionException e) {
						log.warn("failed to update commands on guild {}", guild, e);
					}
				}
			}
		} catch (InterruptedException ignore) {
			Thread.currentThread().interrupt();
		}
	}
	
	private void hotfixRemoveCommandsFromGuild(Guild guild) throws ExecutionException, InterruptedException {
		var commands = guild.retrieveCommands().submit().get();
		log.debug("purging commands in {}", guild.getName());
		for (var command : commands) {
			log.debug("removing command {} ({})", command.getName(), command.getIdLong());
			guild.deleteCommandById(command.getIdLong()).submit().get();
		}
	}
	
	private void registerCommandsOnGuild(Guild guild) throws ExecutionException, InterruptedException {
		/*
			hotfix to remove leaked commands
		 */
		
		var update = guild.updateCommands();
		var chrislieGuild = new DiscordGuild(this, guild);
		
		var ctx = ctxResolver.resolve(Selector::check, chrislieGuild);
		var refs = ctx.listeners().values();
		
		// build list of command data for discord api from context refs
		var commandDatas = new ArrayList<CommandData>();
		for (var ref : refs) {
			var aliases = ref.aliasSet();
			
			// ignore commands without alias (or listeners without commands)
			if (aliases.isEmpty(true))
				continue;
			
			var command = (ChrislieListener.Command) ref.envelope().listener();
			
			// get first alias as primary alias
			var alias = ref.aliasSet().get().values().stream()
					.filter(AliasSet.Alias::exposed)
					.map(AliasSet.Alias::name)
					.findFirst()
					.orElseThrow();
			
			var help = Optional.ofNullable(ref.help());
			if (help.isEmpty()) {
				try {
					help = command.help(ctx, ref);
				} catch (ChrislieListener.ListenerException ignore) {}
			}
			if (help.isEmpty()) {
				help = Optional.of("Keine Hilfe verfügbar.");
			}
			
			commandDatas.add(new CommandData(alias, StringUtils.abbreviate(help.get(), 100))
					.addOption(new OptionData(OptionType.STRING, "args", "Argumente für diesen befehl.")));
		}
		update.addCommands(commandDatas).submit().get();
		log.trace("added {} commands to {}", commandDatas.size(), chrislieGuild);
	}
	
	@SubscribeEvent
	public void onGuildJoin(GuildJoinEvent ev) {
		refreshGuildCommands();
	}
	
	@SubscribeEvent
	public void onSlashCommand(SlashCommandEvent ev) {
		if (sink == null)
			return;
		
		var argsOpt = ev.getOption("args");
		var args = argsOpt == null ? "" : argsOpt.getAsString();

		var slashCommand = new DiscordSlashCommandMessage(this, ev);
		
		/* TODO extend command dispatcher by forcefull invocation that skips prefix detection na takes message straigt as command
		 * requires extending sink
		 * requires writing alternative output that converts output to slash command reply
		 */
		
		// if invoked command is doing asynchronous processing, we need to acknowledge the message ourself
		if (!ev.isAcknowledged())
			ev.acknowledge().submit();
	}
	
	@Override
	public void exit() throws ServiceException {
		@SuppressWarnings("UnnecessaryFinalOnLocalVariableOrParameter") final var helper = new Object() {
			private final CountDownLatch latch = new CountDownLatch(1);
			
			@SubscribeEvent
			public void onShutdown(ShutdownEvent ev) {
				latch.countDown();
			}
		};
		
		// register helper to keep track shutdown event
		jda.addEventListener(helper);
		if (commandUpdater != null)
			commandUpdater.cancel(true);
		jda.removeEventListener(this);
		jda.shutdown();
		
		// wait for shutdown event to occur
		try {
			helper.latch.await();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ServiceException("got interrupted while waiting for jda shutdown", e);
		}
	}
	
	public void traceMessage(@NonNull Message source, @NonNull Message result) {
		var sql = """
					INSERT INTO `discord_message_trace`
					(
						`channelId`, `messageId`,
						`sourceGuildId`, `sourceChannelId`, `sourceMessageId`, `sourceUserNickname`, `sourceUserDiscriminator`, `sourceUserId`,
						`sourceContent`
					) VALUES (
						?, ?, ?, ?, ?, ?, ?, ?, ?
					)
				""";
		
		try (var conn = bot.sharedResources().dataSource().getConnection(); var stmt = conn.prepareStatement(sql)) {
			stmt.setLong(1, result.getChannel().getIdLong());
			stmt.setLong(2, result.getIdLong());
			
			if (source.isFromGuild())
				stmt.setLong(3, source.getGuild().getIdLong());
			else
				stmt.setNull(3, Types.BIGINT);
			
			stmt.setLong(4, source.getChannel().getIdLong());
			stmt.setLong(5, source.getIdLong());
			
			var user = source.getAuthor();
			stmt.setString(6, user.getName());
			stmt.setInt(7, Integer.parseInt(user.getDiscriminator()));
			stmt.setLong(8, user.getIdLong());
			stmt.setString(9, source.getContentRaw());
			
			stmt.executeUpdate();
		} catch (SQLException e) {
			log.error("error inserting message trace to database", e);
		}
	}
	
	public Optional<TraceMessageSource> fetchMessageTrace(@NonNull Message msg) {
		var sql = """
					SELECT `sourceGuildId`, `sourceChannelId`, `sourceMessageId`, `sourceUserNickname`, `sourceUserDiscriminator`, `sourceUserId`, `sourceContent`
					FROM discord_message_trace
					WHERE channelId = ? AND messageId = ?
					LIMIT 1
				""";
		
		try (var conn = bot.sharedResources().dataSource().getConnection(); var stmt = conn.prepareStatement(sql)) {
			stmt.setLong(1, msg.getChannel().getIdLong());
			stmt.setLong(2, msg.getIdLong());
			
			try (var rs = stmt.executeQuery()) {
				if (rs.next())
					return Optional.of(TraceMessageSource.fromResultSet(rs));
			}
		} catch (SQLException e) {
			log.error("error fetching trace from database", e);
		}
		return Optional.empty();
	}
	
	public static boolean isDiscord(ServiceAttached service) {
		return service.service() instanceof DiscordService;
	}
}
