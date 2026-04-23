package traffic;

import traffic.controller.TrafficScheduler;
import traffic.view.ControlPanel;
import traffic.view.SimulationPanel;

import javax.swing.*;
import java.awt.*;

public class Main {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            TrafficScheduler scheduler = TrafficScheduler.getInstance();

            SimulationPanel simPanel = new SimulationPanel(scheduler);
            ControlPanel    ctrlPanel = new ControlPanel(scheduler);
            scheduler.setPanel(simPanel);

            JFrame frame = new JFrame("Smart Traffic Management Simulation");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setLayout(new BorderLayout());
            frame.add(simPanel,  BorderLayout.CENTER);
            frame.add(ctrlPanel, BorderLayout.EAST);

            // Title bar branding
            JPanel titleBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 6));
            titleBar.setBackground(new Color(12, 14, 20));
            JLabel title = new JLabel("◼ SMART TRAFFIC MANAGEMENT  |  Density-Weighted Adaptive Scheduler");
            title.setForeground(new Color(80, 140, 200));
            title.setFont(new Font("Monospaced", Font.BOLD, 12));
            titleBar.add(title);
            frame.add(titleBar, BorderLayout.NORTH);

            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setResizable(false);
            frame.setVisible(true);

            scheduler.start();
        });
    }
}
