package traffic.model;

import traffic.observer.LaneObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class Lane {
    private final int index;
    private final String name;
    private final List<Vehicle> vehicles = new CopyOnWriteArrayList<>();
    private final List<LaneObserver> observers = new ArrayList<>();
    private static final int DENSITY_THRESHOLD = 3;

    public Lane(int index, String name) {
        this.index = index;
        this.name  = name;
    }

    public void addVehicle(Vehicle v) {
        vehicles.add(v);
        notifyObservers();
    }

    public void removeVehicle(Vehicle v) {
        vehicles.remove(v);
        notifyObservers();
    }

    public boolean hasAmbulance() {
        return vehicles.stream().anyMatch(Vehicle::isAmbulance);
    }

    public int getDensity() { return vehicles.size(); }

    public int getIndex()          { return index; }
    public String getName()        { return name; }
    public List<Vehicle> getVehicles() { return vehicles; }

    public void addObserver(LaneObserver o)    { observers.add(o); }
    public void removeObserver(LaneObserver o) { observers.remove(o); }

    private void notifyObservers() {
        for (LaneObserver o : observers) o.onDensityChanged(this);
    }
}
