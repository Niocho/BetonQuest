package org.betonquest.betonquest.objectives;

import org.betonquest.betonquest.BetonQuest;
import org.betonquest.betonquest.Instruction;
import org.betonquest.betonquest.VariableNumber;
import org.betonquest.betonquest.api.Objective;
import org.betonquest.betonquest.api.logger.BetonQuestLogger;
import org.betonquest.betonquest.api.profiles.OnlineProfile;
import org.betonquest.betonquest.api.profiles.Profile;
import org.betonquest.betonquest.exceptions.InstructionParseException;
import org.betonquest.betonquest.id.EventID;
import org.betonquest.betonquest.id.ObjectiveID;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The folder objective is the implementation like folder event, the main difference is that
 * this objective only executes while player is online. If the player quits the server while executing,
 * the objective will record where the objective stops and pick it up to execute when the player rejoins the server.
 * This objective is suitable to create some important events required to be executed sequentially with
 * some intervals.
 */
public class FolderObjective extends Objective {
    /**
     * Custom {@link BetonQuestLogger} instance for this class.
     */
    @NotNull
    private final BetonQuestLogger log = BetonQuest.getInstance().getLoggerFactory().create(this.getClass());

    /**
     * The interval between two checks
     */
    private final int interval;

    /**
     * The delay to apply before running the events.
     */
    @NotNull
    private final VariableNumber delay;

    /**
     * The delay to apply between each event.
     */
    @NotNull
    private final VariableNumber period;

    /**
     * Whether the delay and period are in ticks.
     */
    private final boolean ticks;

    /**
     * Whether the delay and period are in minutes.
     */
    private final boolean minutes;

    /**
     * The runnable to check player's events running status
     */
    @Nullable
    private BukkitTask runnable;

    /**
     * @param instruction the instruction
     * @throws InstructionParseException if the events are empty
     */
    public FolderObjective(final Instruction instruction) throws InstructionParseException {
        super(instruction);
        template = FolderData.class;

        interval = instruction.getInt(instruction.getOptional("interval"), 20);
        if (events.length == 0) {
            throw new InstructionParseException("Error in folder objective '" + instruction.getID() + "': events are empty");
        }
        delay = instruction.getVarNum(instruction.getOptional("delay", "0"));
        period = instruction.getVarNum(instruction.getOptional("period", "2"));
        ticks = instruction.hasArgument("ticks");
        minutes = instruction.hasArgument("minuets");
    }

    /**
     * Get the ticks before the first event to be run
     *
     * @param profile profile of the player
     * @return the ticks
     */
    private int getTicksDelay(@NotNull final Profile profile) {
        if (!ticks && !minutes) {
            return (int) (delay.getDouble(profile) * 20);
        }
        if (!ticks) {
            return (int) ((delay.getDouble(profile) * 60) * 20);
        }
        return delay.getInt(profile);
    }

    /**
     * Get the ticks between two events to be run
     *
     * @param profile profile of the player
     * @return the ticks
     */
    private int getTicksPeriod(@NotNull final Profile profile) {
        if (!ticks && !minutes) {
            return (int) (period.getDouble(profile) * 20);
        }
        if (!ticks) {
            return (int) (period.getDouble(profile) * 60 * 20);
        }
        return period.getInt(profile);
    }

    /**
     * Gets the current event, if not found, will return the first event.
     * The not found situation often happens when server administers change the configuration
     * and remove some events. This will lead to a warning message if above happens.
     *
     * @param data the data
     * @return the event id that currently to be run
     */
    @NotNull
    private EventID findCurrent(@NotNull final FolderData data) {
        for (final EventID event : events) {
            if (event.getFullID().equals(data.currentEvent)) {
                return event;
            }
        }

        log.warn("Error in folder objective '" + instruction.getID() + "': cannot find event with id '" + data.currentEvent + "'");
        return events[0];
    }

    /**
     * Find the next event need to be run
     * Why don't we use index to mark the running status?
     * The main reason is that sometimes the server administers may change the
     * events. if use index, we might run into exceptions. To minimize the influence of
     * the change, we iterate through the events and trying to recover the closest event result.
     *
     * @param eventID current event id
     * @return the next event
     */
    @NotNull
    private Optional<EventID> findNextEvent(@NotNull final EventID eventID) {
        var next = false;
        for (final EventID event : events) {
            if (eventID.equals(event)) {
                next = true;
                continue;
            }
            if (next) {
                return Optional.of(event);
            }
        }

        return Optional.empty();
    }

    /**
     * Judge if the event is the first.
     *
     * @param eventID event to be judged.
     * @return if the event is the first.
     */
    private boolean isFirstEvent(@NotNull final EventID eventID) {
        return events[0].equals(eventID);
    }

    /**
     * Execute the current event and set the cursor to the next event
     *
     * @param folderData    the data
     * @param onlineProfile the player's online profile
     * @param currentEvent  the event to be executed
     * @param players       the players need to be removed from this objective
     * @param isFirst       if the event to be executed is the first of the events list
     */
    private void executeEventAndSetNext(@NotNull final FolderData folderData, @NotNull final OnlineProfile onlineProfile, @NotNull final EventID currentEvent, @NotNull final List<Profile> players, final boolean isFirst) {
        if ((System.currentTimeMillis() - folderData.lastExecuteTime) / 1000.0 * 20.0 > (isFirst ? getTicksDelay(onlineProfile) : getTicksPeriod(onlineProfile))) {
            BetonQuest.event(onlineProfile, currentEvent);

            final var next = findNextEvent(currentEvent);
            if (next.isPresent()) {
                folderData.setLastExecuteTime(System.currentTimeMillis());
                folderData.setCurrentEvent(next.get().getFullID());
            } else {
                players.add(onlineProfile);
            }
        }
    }

    @Override
    public void start() {
        runnable = new BukkitRunnable() {
            @Override
            public void run() {
                final var players = new LinkedList<Profile>();

                for (final Map.Entry<Profile, ObjectiveData> entry : dataMap.entrySet()) {
                    final var onlineProfileOpt = entry.getKey().getOnlineProfile();
                    onlineProfileOpt.ifPresent((onlineProfile) -> {
                        final var folderData = (FolderData) entry.getValue();
                        final var currentEvent = findCurrent(folderData);
                        if (isFirstEvent(currentEvent)) {
                            if (!checkConditions(onlineProfile)) {
                                return;
                            }
                            executeEventAndSetNext(folderData, onlineProfile, currentEvent, players, true);
                        } else {
                            executeEventAndSetNext(folderData, onlineProfile, currentEvent, players, false);
                        }
                    });
                }

                for (final Profile player : players) {
                    completeObjectiveForPlayer(player);
                    BetonQuest.getInstance().getPlayerData(player).removeRawObjective((ObjectiveID) instruction.getID());
                }
            }
        }.runTaskTimer(BetonQuest.getInstance(), 0, interval);
    }

    @Override
    public void stop() {
        if (this.runnable != null) {
            this.runnable.cancel();
        }
    }

    @Override
    public String getDefaultDataInstruction(final Profile profile) {
        return events[0].getFullID() + "," + System.currentTimeMillis();
    }

    @Override
    public String getDefaultDataInstruction() {
        //Empty to satisfy bad API needs
        return null;
    }

    @Override
    public String getProperty(final String name, final Profile profile) {
        final var data = (FolderData) dataMap.get(profile);
        if (data == null) {
            return "";
        }
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "current" -> data.currentEvent;
            case "lastexec" -> data.lastExecuteTime.toString();
            default -> "";
        };
    }

    /**
     * A data of current running status.
     */
    public static class FolderData extends ObjectiveData {
        /**
         * The event need to be run.
         */
        private String currentEvent;
        /**
         * Last event's execution time.
         */

        private Long lastExecuteTime;

        /**
         * Creates a new data
         *
         * @param instruction the instruction
         * @param profile     the player profile
         * @param objID       the objective id
         */
        public FolderData(final String instruction, final Profile profile, final String objID) {
            super(instruction, profile, objID);
            final var instructions = instruction.split(",");
            currentEvent = instructions[0];
            lastExecuteTime = Long.parseLong(instructions[1]);
        }

        /**
         * set the next event need to be run
         *
         * @param currentEvent the event
         */
        private void setCurrentEvent(final String currentEvent) {
            this.currentEvent = currentEvent;
            this.instruction = currentEvent + "," + this.lastExecuteTime;
            update();
        }

        /**
         * set the last event execution time
         *
         * @param lastExecuteTime the execution time
         */
        private void setLastExecuteTime(final Long lastExecuteTime) {
            this.lastExecuteTime = lastExecuteTime;
            this.instruction = this.currentEvent + "," + this.lastExecuteTime;
            update();
        }
    }
}
