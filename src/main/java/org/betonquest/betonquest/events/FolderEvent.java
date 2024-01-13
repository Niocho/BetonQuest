package org.betonquest.betonquest.events;

import org.betonquest.betonquest.BetonQuest;
import org.betonquest.betonquest.Instruction;
import org.betonquest.betonquest.VariableNumber;
import org.betonquest.betonquest.api.QuestEvent;
import org.betonquest.betonquest.api.logger.BetonQuestLogger;
import org.betonquest.betonquest.api.profiles.Profile;
import org.betonquest.betonquest.exceptions.InstructionParseException;
import org.betonquest.betonquest.exceptions.ObjectNotFoundException;
import org.betonquest.betonquest.exceptions.QuestRuntimeException;
import org.betonquest.betonquest.id.EventID;
import org.betonquest.betonquest.utils.PlayerConverter;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Folder event is a collection of other events, that can be run after a delay and the events can be randomly chosen to
 * run or not.
 */
@SuppressWarnings("PMD.GodClass")
public class FolderEvent extends QuestEvent {
    /**
     * Custom {@link BetonQuestLogger} instance for this class.
     */
    private final BetonQuestLogger log = BetonQuest.getInstance().getLoggerFactory().create(this.getClass());

    /**
     * Random generator used to choose events to run.
     */
    private final Random randomGenerator = new Random();

    /**
     * The delay to apply before running the events.
     */
    private final VariableNumber delay;

    /**
     * The delay to apply between each event.
     */
    private final VariableNumber period;

    /**
     * The number of events to run.
     */
    private final VariableNumber random;

    /**
     * The events to run.
     */
    private final EventID[] events;

    /**
     * Whether the delay and period are in ticks.
     */
    private final boolean ticks;

    /**
     * Whether the delay and period are in minutes.
     */
    private final boolean minutes;

    /**
     * The execution mode of this folder event
     */
    private final ExecutionMode executionMode;

    /**
     * The constructor called by BetonQuest via reflection.
     *
     * @param instruction the instruction to parse
     * @throws InstructionParseException if the instruction is invalid
     */
    public FolderEvent(final Instruction instruction) throws InstructionParseException {
        super(instruction, false);
        staticness = true;
        persistent = true;
        events = instruction.getList(instruction::getEvent).toArray(new EventID[0]);
        delay = instruction.getVarNum(instruction.getOptional("delay"));
        period = instruction.getVarNum(instruction.getOptional("period"));
        random = instruction.getVarNum(instruction.getOptional("random"));
        ticks = instruction.hasArgument("ticks");
        minutes = instruction.hasArgument("minutes");
        executionMode = ExecutionMode.parse(instruction.getOptional("executionMode", "default"));
        JoinListener.register(new JoinListener());
    }

    /**
     * The mode of the folder event
     */
    private enum ExecutionMode {
        /**
         * The default execution mode of folder
         */
        DEFAULT,
        /**
         * If the player quits during execution, the event is cancelled
         */
        CANCEL_ON_LOGOUT,
        /**
         * Will ensure every single event be executed during the player's online time
         */
        ENSURE_EXECUTION;

        public static ExecutionMode parse(@NotNull final String mode) throws InstructionParseException {
            switch (mode.toLowerCase(Locale.ROOT)) {
                case "default" -> {
                    return DEFAULT;
                }
                case "cancelonlogout" -> {
                    return CANCEL_ON_LOGOUT;
                }
                case "ensureexecution" -> {
                    return ENSURE_EXECUTION;
                }
                default ->
                        throw new InstructionParseException("There is no such " + ExecutionMode.class.getSimpleName() + ": " + mode);
            }
        }
    }

    @SuppressWarnings({"PMD.CyclomaticComplexity", "PMD.NPathComplexity", "PMD.CognitiveComplexity"})
    @Override
    protected Void execute(final Profile profile) throws QuestRuntimeException {
        final Deque<EventID> chosenList = new LinkedList<>();
        // choose randomly which events should be fired
        final int randomInt = random == null ? 0 : random.getInt(profile);
        if (randomInt > 0 && randomInt <= events.length) {
            // copy events into the modifiable ArrayList
            final ArrayList<EventID> eventsList = new ArrayList<>(Arrays.asList(events));
            // remove chosen events from that ArrayList and place them in a new
            // list
            for (int i = randomInt; i > 0; i--) {
                final int chosen = randomGenerator.nextInt(eventsList.size());
                chosenList.add(eventsList.remove(chosen));
            }
        } else {
            chosenList.addAll(Arrays.asList(events));
        }

        final Long execDelay = getInTicks(delay, profile);
        final Long execPeriod = getInTicks(period, profile);

        if (execDelay == null && execPeriod == null) {
            for (final EventID event : chosenList) {
                BetonQuest.event(profile, event);
            }
        } else if (execPeriod == null) {
            final FolderEventCanceller eventCanceller = createFolderEventCanceller(profile);
            if (!usingEnsureMode(profile)) {
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        eventCanceller.destroy();
                        if (eventCanceller.isCancelled()) {
                            return;
                        }
                        for (final EventID event : chosenList) {
                            BetonQuest.event(profile, event);
                        }
                    }
                }.runTaskLater(BetonQuest.getInstance(), execDelay);
            }
        } else {
            if (execDelay == null && !chosenList.isEmpty()) {
                final EventID event = chosenList.removeFirst();
                BetonQuest.event(profile, event);
            }
            if (!chosenList.isEmpty()) {
                final FolderEventCanceller eventCanceller = createFolderEventCanceller(profile);
                if (!usingEnsureMode(profile)) {
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            final EventID event = chosenList.pollFirst();
                            if (eventCanceller.isCancelled() || event == null) {
                                eventCanceller.destroy();
                                this.cancel();
                                return;
                            }
                            BetonQuest.event(profile, event);
                        }
                    }.runTaskTimer(BetonQuest.getInstance(), execDelay == null ? execPeriod : execDelay, execPeriod);
                }
            }
        }
        return null;
    }

    private boolean usingEnsureMode(@NotNull final Profile profile) {
        final Long execDelay = getInTicks(delay, profile);
        final Long execPeriod = getInTicks(period, profile);
        if (executionMode == ExecutionMode.ENSURE_EXECUTION) {
            setNextEvent(profile, events[0]);
            new BukkitRunnable() {
                @Override
                public void run() {
                    ensureExecute(profile);
                }
            }.runTaskLater(BetonQuest.getInstance(), execDelay == null ? execPeriod : execDelay);
            return true;
        }

        return false;
    }

    /**
     * this function is used to safely execute player's events
     *
     * @param profile the player's profile
     */
    @SuppressWarnings("PMD.CognitiveComplexity")
    protected void ensureExecute(final Profile profile) {
        final var nextEvent = getNextEvent(profile);
        if (nextEvent == null) {
            return;
        }
        if (profile.getOnlineProfile().isEmpty()) {
            return;
        }

        final Long execPeriod = getInTicks(period, profile);
        if (execPeriod == null) {
            var start = false;
            if (profile.getPlayer().isOnline() && profile.getOnlineProfile().isPresent()) {
                final var onlineProfile = profile.getOnlineProfile().get();
                for (final EventID event : events) {
                    if (event.equals(nextEvent)) {
                        start = true;
                        BetonQuest.event(onlineProfile, event);
                        moveToNextEvent(onlineProfile, event);
                        continue;
                    }

                    if (start) {
                        BetonQuest.event(onlineProfile, event);
                        moveToNextEvent(onlineProfile, event);
                    }
                }
            }
        } else {
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (profile.getPlayer().isOnline() && profile.getOnlineProfile().isPresent()) {
                        final var onlineProfile = profile.getOnlineProfile().get();
                        final var eventToExecuted = getNextEvent(profile);
                        if (eventToExecuted != null) {
                            for (final EventID event : events) {
                                if (event.equals(eventToExecuted)) {
                                    BetonQuest.event(onlineProfile, eventToExecuted);
                                    moveToNextEvent(onlineProfile, eventToExecuted);
                                    break;
                                }
                            }
                        } else {
                            this.cancel();
                        }
                    } else {
                        this.cancel();
                    }
                }
            }.runTaskTimer(BetonQuest.getInstance(), 0, execPeriod);
        }
    }

    private FolderEventCanceller createFolderEventCanceller(final Profile profile) {
        if (executionMode == ExecutionMode.CANCEL_ON_LOGOUT) {
            return new QuitListener(log, profile);
        } else {
            return () -> false;
        }
    }

    private Long getInTicks(final VariableNumber timeVariable, final Profile profile) {
        if (timeVariable == null) {
            return null;
        }

        long time = timeVariable.getInt(profile);
        if (time == 0) {
            return null;
        }

        if (minutes) {
            time *= 20 * 60;
        } else if (!ticks) {
            time *= 20;
        }
        return time;
    }

    @Nullable
    private EventID getNextEvent(@NotNull final Profile profile) {
        final var fullId = getFullId();
        for (final String tag : BetonQuest.getInstance().getPlayerData(profile).getTags()) {
            if (tag.startsWith(fullId)) {
                final var eventMark = tag.substring(fullId.length());
                if (eventMark.contains("#") && eventMark.contains("ensuredata")) {
                    final var data = Arrays.stream(eventMark.split("#")).filter((it) -> !it.isBlank())
                            .toList();
                    try {
                        return new EventID(null, data.get(0));
                    } catch (final ObjectNotFoundException e) {
                        return null;
                    }
                } else {
                    return null;
                }
            }
        }
        return null;
    }

    private void setNextEvent(@NotNull final Profile profile, final EventID eventID) {
        final var fullId = getFullId();
        final var playerData = BetonQuest.getInstance().getPlayerData(profile);
        playerData.addTag(fullId + "#" + eventID + "#ensuredata");
    }

    private void removeNextEvent(@NotNull final Profile profile, final EventID eventToBeRemoved) {
        final var playerData = BetonQuest.getInstance().getPlayerData(profile);
        for (final String tag : playerData.getTags()) {
            if (tag.startsWith(this.getFullId() + "#" + eventToBeRemoved.toString())) {
                playerData.removeTag(tag);
            }
        }
    }

    private void moveToNextEvent(@NotNull final Profile profile, final EventID currentEvent) {
        var found = false;
        for (final EventID event : events) {
            if (found) {
                removeNextEvent(profile, currentEvent);
                setNextEvent(profile, event);
                return;
            }
            if (event.equals(currentEvent)) {
                found = true;
            }
        }

        if (found) {
            removeNextEvent(profile, currentEvent);
        }
    }

    /**
     * Interface to check if an execution of a folder event is cancelled.
     */
    private interface FolderEventCanceller {
        /**
         * Whether the execution of the folder event should be cancelled.
         *
         * @return true if the event needs to be cancelled; false otherwise
         */
        boolean isCancelled();

        /**
         * Clean up any resources used by the canceller if necessary.
         */
        default void destroy() {
            // Empty
        }
    }

    /**
     * Registers the quit listener if the event should be cancelled on logout.
     */
    private static class QuitListener implements FolderEventCanceller, Listener {
        /**
         * Custom {@link BetonQuestLogger} instance for this class.
         */
        private final BetonQuestLogger log;

        /**
         * The profile of the player to check for.
         */
        private final Profile profile;

        /**
         * Whether the event is cancelled.
         */
        private boolean cancelled;

        /**
         * Create a quit listener for the given profile's player.
         *
         * @param log     logger for debug messages
         * @param profile profile to check for
         */
        public QuitListener(final BetonQuestLogger log, final Profile profile) {
            this.log = log;
            this.profile = profile;
            BetonQuest.getInstance().getServer().getPluginManager().registerEvents(this, BetonQuest.getInstance());
        }

        /**
         * Handle quit events to check if an execution of the folder event needs to be cancelled.
         *
         * @param event player quit event to handle
         */
        @EventHandler
        public void onPlayerQuit(final PlayerQuitEvent event) {
            if (event.getPlayer().getUniqueId().equals(profile.getPlayerUUID())) {
                cancelled = true;
                log.debug("Folder event cancelled due to disconnect of " + profile);
            }
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void destroy() {
            PlayerQuitEvent.getHandlerList().unregister(this);
        }
    }

    /**
     * This listener listen to player join events
     * to continue any events that player has not executed.
     * In order to unregister this event in time, each time
     * this listener executed, it will check if the event itself is removed.
     * If so, the listener will also unregister itself.
     */
    private class JoinListener implements Listener {
        /**
         * Avoid to register two save listeners for one folder event
         */
        private static final Map<String, JoinListener> REGISTERED = new ConcurrentHashMap<>();

        private static void register(final JoinListener listener) {
            REGISTERED.compute(listener.getEventId(), (k, currentListener) -> {
                if (currentListener != null) {
                    PlayerJoinEvent.getHandlerList().unregister(currentListener);
                }
                Bukkit.getServer().getPluginManager().registerEvents(
                        listener,
                        BetonQuest.getInstance()
                );
                return listener;
            });
        }

        /**
         * Create the join listener
         */
        public JoinListener() {
        }

        /**
         * The event id the listener's parent folder event holds
         *
         * @return the event id
         */
        public String getEventId() {
            return FolderEvent.this.getFullId();
        }

        /**
         * Called when player join, will trigger the execution
         * of events that haven't been finished yet.
         *
         * @param event the event
         */
        @EventHandler
        public void onPlayerJoin(final PlayerJoinEvent event) {
            try {
                new EventID(null, getFullId());
            } catch (final ObjectNotFoundException e) {
                PlayerJoinEvent.getHandlerList().unregister(this);
                REGISTERED.remove(getFullId());
                return;
            }
            final var profile = PlayerConverter.getID(event.getPlayer());
            ensureExecute(profile);
        }
    }
}
