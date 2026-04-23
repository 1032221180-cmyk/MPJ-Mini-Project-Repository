package traffic.controller;

import traffic.model.*;
import traffic.observer.LaneObserver;
import traffic.strategy.GreenTimeStrategy;
import traffic.view.SimulationPanel;

import javax.swing.Timer;
import java.util.*;

/**
 * Singleton Scheduler.
 * Owns the tick loop, manages light phase transitions,
 * implements Observer to react to density spikes,
 * and handles ambulance emergency overrides.
 */
public class TrafficScheduler implements LaneObserver {

    private static TrafficScheduler instance;

    // ── Intersection geometry ──────────────────────────────────────────────
    public static final int LANES = 4;
    // Lane indices: 0=North, 1=South, 2=East, 3=West
    // Pairs that share green: (0,1) NS  or  (2,3) EW
    private static final int[][] GREEN_PAIRS = { {0, 1}, {2, 3} };

    private final Lane[]         lanes  = new Lane[LANES];
    private final TrafficLight[] lights = new TrafficLight[LANES];

    private int  activePair    = 0;   // which pair is currently GREEN
    private boolean yellowPhase = false;
    private int  yellowTicks   = 0;
    private static final int YELLOW_DURATION = 15; // ticks

    private boolean emergencyActive = false;
    private int     emergencyLane   = -1;

    private GreenTimeStrategy strategy;
    private final List<String> eventLog = new ArrayList<>();

    private SimulationPanel panel;
    private final Timer tickTimer;
    private static final int TICK_MS = 80;

    // Vehicle spawn/movement
    private final Random rng = new Random();
    private int spawnCooldown = 0;

    private TrafficScheduler() {
        // Init lanes
        String[] names = {"North", "South", "East", "West"};
        for (int i = 0; i < LANES; i++) {
            lanes[i] = new Lane(i, names[i]);
            lanes[i].addObserver(this);
            lights[i] = new TrafficLight(i);
        }

        strategy = new GreenTimeStrategy.DensityWeighted();

        // Start first green pair
        activateGreenPair(activePair);

        tickTimer = new Timer(TICK_MS, e -> tick());
    }

    public static TrafficScheduler getInstance() {
        if (instance == null) instance = new TrafficScheduler();
        return instance;
    }

    public void setPanel(SimulationPanel p) { this.panel = p; }

    public void start() { tickTimer.start(); }
    public void stop()  { tickTimer.stop(); }

    // ── Observer callback ──────────────────────────────────────────────────
    @Override
    public void onDensityChanged(Lane lane) {
        // Ambulance check — override immediately
        if (lane.hasAmbulance() && !emergencyActive) {
            triggerEmergency(lane.getIndex());
        }
    }

    // ── Main tick ──────────────────────────────────────────────────────────
    private void tick() {
        moveVehicles();
        spawnVehicles();

        if (emergencyActive) {
            tickEmergency();
        } else {
            tickNormal();
        }

        if (panel != null) panel.repaint();
    }

    private void tickNormal() {
        if (yellowPhase) {
            yellowTicks--;
            if (yellowTicks <= 0) {
                yellowPhase = false;
                // Advance to next pair
                activePair  = (activePair + 1) % GREEN_PAIRS.length;
                activateGreenPair(activePair);
            }
            return;
        }

        // Tick the active lights
        boolean expired = false;
        for (int li : GREEN_PAIRS[activePair]) {
            if (lights[li].tick()) expired = true;
        }

        if (expired) {
            // Enter yellow phase
            yellowPhase = true;
            yellowTicks = YELLOW_DURATION;
            for (int li : GREEN_PAIRS[activePair]) {
                lights[li].setState(LightState.YELLOW);
            }
            log("Phase switch → YELLOW for pair " + activePair);
        }
    }

    private void activateGreenPair(int pair) {
        // All lights RED first
        for (int i = 0; i < LANES; i++) lights[i].setState(LightState.RED);

        int[] active = GREEN_PAIRS[pair];
        for (int li : active) {
            int ticks = strategy.computeGreenTicks(lanes[li]);
            lights[li].setState(LightState.GREEN, ticks);
        }
        log("GREEN → " + lanes[active[0]].getName() + "/" + lanes[active[1]].getName()
            + " (" + lights[active[0]].getGreenTimeRemaining() + " ticks)");
    }

    // ── Emergency override ────────────────────────────────────────────────
    public void triggerEmergency(int laneIdx) {
        emergencyActive = true;
        emergencyLane   = laneIdx;
        yellowPhase     = false;

        for (int i = 0; i < LANES; i++) lights[i].setState(LightState.RED);
        lights[laneIdx].setState(LightState.GREEN, 60);

        log("🚨 EMERGENCY OVERRIDE → Lane " + lanes[laneIdx].getName());
    }

    private void tickEmergency() {
        boolean expired = lights[emergencyLane].tick();
        if (expired || !lanes[emergencyLane].hasAmbulance()) {
            emergencyActive = false;
            log("Emergency cleared. Resuming normal schedule.");
            activateGreenPair(activePair);
        }
    }

    // ── Vehicle movement ─────────────────────────────────────────────────
    private void moveVehicles() {
        for (int i = 0; i < LANES; i++) {
            LightState state = lights[i].getState();
            boolean canMove  = (state == LightState.GREEN);

            List<Vehicle> toRemove = new ArrayList<>();
            for (Vehicle v : lanes[i].getVehicles()) {
                if (canMove || v.isAmbulance()) {
                    v.setMoving(true);
                    v.update();
                    // Remove if it exits the visible area
                    if (isOutOfBounds(v)) toRemove.add(v);
                } else {
                    v.setMoving(false);
                    // Still update position if ambulance ignores red
                    if (v.isAmbulance()) {
                        v.setMoving(true);
                        v.update();
                        if (isOutOfBounds(v)) toRemove.add(v);
                    }
                }
            }
            for (Vehicle v : toRemove) lanes[i].removeVehicle(v);
        }
    }

    private boolean isOutOfBounds(Vehicle v) {
        return v.getX() < -30 || v.getX() > SimulationPanel.W + 30
            || v.getY() < -30 || v.getY() > SimulationPanel.H + 30;
    }

    // ── Vehicle spawning ─────────────────────────────────────────────────
    private void spawnVehicles() {
        if (spawnCooldown-- > 0) return;
        spawnCooldown = 8 + rng.nextInt(12);

        int laneIdx = rng.nextInt(LANES);
        if (lanes[laneIdx].getDensity() >= 8) return; // cap per lane

        Vehicle v = buildVehicle(laneIdx, Vehicle.Type.CAR);
        lanes[laneIdx].addVehicle(v);
    }

    public void spawnAmbulance(int laneIdx) {
        Vehicle v = buildVehicle(laneIdx, Vehicle.Type.AMBULANCE);
        lanes[laneIdx].addVehicle(v);
        log("Ambulance spawned → Lane " + lanes[laneIdx].getName());
    }

    public void spawnCarInLane(int laneIdx) {
        if (lanes[laneIdx].getDensity() >= 10) return;
        Vehicle v = buildVehicle(laneIdx, Vehicle.Type.CAR);
        lanes[laneIdx].addVehicle(v);
    }

    /**
     * Build vehicle with start position and target on the opposite side.
     * Lane layout:
     *   0 = North → moving downward  (top → center → bottom)
     *   1 = South → moving upward    (bottom → center → top)
     *   2 = East  → moving leftward  (right → center → left)
     *   3 = West  → moving rightward (left → center → right)
     */
    private Vehicle buildVehicle(int laneIdx, Vehicle.Type type) {
        int cx = SimulationPanel.W / 2;
        int cy = SimulationPanel.H / 2;
        double speed = (type == Vehicle.Type.AMBULANCE) ? 3.5 : 1.5 + rng.nextDouble();

        double sx, sy, tx, ty;
        int offset = (rng.nextBoolean()) ? -20 : 20; // lane offset within road
        switch (laneIdx) {
            case 0 -> { sx = cx + offset; sy = 0;                tx = cx + offset; ty = SimulationPanel.H; }
            case 1 -> { sx = cx - offset; sy = SimulationPanel.H; tx = cx - offset; ty = 0; }
            case 2 -> { sx = SimulationPanel.W; sy = cy + offset; tx = 0;           ty = cy + offset; }
            default-> { sx = 0;           sy = cy - offset; tx = SimulationPanel.W; ty = cy - offset; }
        }

        Vehicle v = new Vehicle(type, sx, sy, speed, laneIdx);
        v.setTarget(tx, ty);
        return v;
    }

    // ── Accessors ─────────────────────────────────────────────────────────
    public Lane[]         getLanes()  { return lanes; }
    public TrafficLight[] getLights() { return lights; }
    public List<String>   getLog()    { return eventLog; }
    public boolean        isEmergencyActive() { return emergencyActive; }
    public int            getActivePair()     { return activePair; }
    public boolean        isYellowPhase()     { return yellowPhase; }

    public void setStrategy(GreenTimeStrategy s) {
        this.strategy = s;
        log("Strategy switched → " + s.getClass().getSimpleName());
    }

    private void log(String msg) {
        eventLog.add(0, msg);
        if (eventLog.size() > 20) eventLog.remove(eventLog.size() - 1);
    }

    /** Reset singleton for fresh start */
    public static void reset() { instance = null; }
}
