package mc.mrd_og.redbug.vcd;

import mc.mrd_og.redbug.objects.monitor.Monitor;
import mc.mrd_og.redbug.objects.node.Node;
import mc.mrd_og.redbug.objects.node.NodeManager;
import mc.mrd_og.redbug.util.VcdIdGenerator;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

public class VcdManager {
    private NodeManager nodeManager;
    private static VcdManager vcdManager;

    HashSet<Monitor> allMonitors = new HashSet<>();
    HashMap<Monitor, BufferedWriter> writers = new HashMap<>();
    HashMap<Monitor, Integer> timestamps = new HashMap<>();

    HashMap<Monitor, HashMap<Node, ArrayList<Integer>>> lastWireValues = new HashMap<>();
    HashMap<Monitor, HashMap<Node, String>> lastBusValues = new HashMap<>();

    private final HashMap<Monitor, HashMap<Node, String>> busIds = new HashMap<>();
    private final HashMap<Monitor, HashMap<Node, ArrayList<String>>> wireIds = new HashMap<>();

    public static VcdManager getInstance() {
        if (vcdManager == null)
            vcdManager = new VcdManager();
        return vcdManager;
    }

    private VcdManager() {

    }

    public void setNodeManager(NodeManager nodeManager) {
        this.nodeManager = nodeManager;
    }

    public boolean doesntHaveMonitor(Monitor monitor) {
        return !allMonitors.contains(monitor);
    }

    public boolean hasWriter(Monitor monitor) {
        if (doesntHaveMonitor(monitor))
            return false;

        if (!writers.containsKey(monitor))
            return false;

        return writers.get(monitor) != null;
    }

    public boolean startDump(Player player, Monitor monitor) {

        if (doesntHaveMonitor(monitor)) {
            allMonitors.add(monitor);
        }

        if (hasWriter(monitor)) {
            player.sendMessage(Component.text("[RB]: Monitor " + monitor.getName() + " is already writing to VCD", NamedTextColor.RED));
            return false;
        }

        timestamps.put(monitor, 0);
        busIds.put(monitor, new HashMap<>());
        wireIds.put(monitor, new HashMap<>());
        lastWireValues.put(monitor, new HashMap<>());
        lastBusValues.put(monitor, new HashMap<>());

        // Create writer
        if (!initialiseWriter(monitor)) {
            player.sendMessage(Component.text("[RB]: Monitor " + monitor.getName() + " could not generate vcd file", NamedTextColor.RED));
            return false;
        }

        try {
            writeHeader(player, monitor);
            writeInitialDump(player, monitor);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        player.sendMessage(Component.text("[RB]: Monitor " + monitor.getName() + " VCD generation started", NamedTextColor.AQUA));
        return true;
    }

    private boolean initialiseWriter(Monitor monitor) {

        String directoryPath = "monitors/";

        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy_MM_dd_HH_mm_ss"));
        String fileName = monitor.getName() + "_" + time + ".vcd";

        File file = new File(directoryPath + fileName);

        try {
            // Create parent directories if they don't exist
            File parentDir = file.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            boolean created = file.createNewFile();

            if (!created) {
                return false;
            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        BufferedWriter writer;
        try {
            writer = new BufferedWriter(new FileWriter(file));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        writers.put(monitor, writer);

        Path absPath = file.toPath().toAbsolutePath();

        String strippedFileName = fileName.replace(".vcd", "");
        try {
            writeTclScript(absPath.resolveSibling(strippedFileName + "_reload.tcl"));
            writeLaunchScript(absPath.resolveSibling(strippedFileName + "_live.sh"), fileName, strippedFileName + "_reload.tcl");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return true;
    }

    public void stopDump(Monitor monitor) {
        closeWriter(writers.get(monitor));
        cleanMonitor(monitor);
    }

    private void cleanMonitor(Monitor monitor) {
        writers.remove(monitor);
        allMonitors.remove(monitor);
        busIds.remove(monitor);
        wireIds.remove(monitor);
        lastWireValues.remove(monitor);
        lastBusValues.remove(monitor);
    }

    public synchronized void closeWriter(BufferedWriter writer) {
        try {
            writer.flush();
        } catch (Exception ignored) { }
        try {
            writer.close();
        } catch (Exception ignored) { }
    }

    public void stop() {
        // Close all streams
        for (Monitor monitor : allMonitors) {
            stopDump(monitor);
        }

        writers.clear();
        allMonitors.clear();
    }

    private void writeHeader(Player player, Monitor monitor) throws IOException {

        if (doesntHaveMonitor(monitor))
            return;

        BufferedWriter writer = writers.get(monitor);
        VcdIdGenerator vcdIdGenerator = new VcdIdGenerator();

        writer.write("$version Redbug Oscilloscope $end\n");
        writer.write("$date " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " $end\n");
        writer.write("$timescale 100ms $end\n");
        writer.write("$scope module redbug $end\n");

        ArrayList<String> nodeNames = monitor.getMonitorSources();

        for (String nodeName : nodeNames) {
            // Start scope
            writer.write("$scope module " + nodeName + " $end\n");

            Node node = nodeManager.getNode(player, nodeName);

            int bitCount = node.getLocations().size();

            wireIds.get(monitor).put(node, new ArrayList<>());

            // Generate ids for all wires
            for (int i = 0; i < bitCount; i++) {
                String wireName = nodeName + "_bit_" + (bitCount - 1 - i);
                String id = vcdIdGenerator.next();
                wireIds.get(monitor).get(node).add(id);
                writer.write("$var wire 1 " + id + " " + wireName + " $end\n");
            }

            if (bitCount > 1) {
                String id = vcdIdGenerator.next();
                busIds.get(monitor).put(node, id);
                writer.write("$var reg " + bitCount + " " + id + " bus[" + (bitCount - 1) + ":0] $end\n");
            }

            // End scope
            writer.write("$upscope $end\n");
        }
        // Finish definitions
        writer.write("$enddefinitions $end\n");
        writer.flush();
    }

    private void writeInitialDump(Player player, Monitor monitor) throws IOException {
        BufferedWriter writer = writers.get(monitor);

        writer.write("#0\n");
        writer.write("$dumpvars\n");

        HashMap<Node, ArrayList<String>> monitorWireIds = wireIds.get(monitor);
        HashMap<Node, String> monitorBusIds = busIds.get(monitor);

        ArrayList<String> nodeNames = monitor.getMonitorSources();

        for (String nodeName : nodeNames) {
            Node node = nodeManager.getNode(player, nodeName);

            ArrayList<Integer> values = node.getCurrentLevels();

            ArrayList<String> nodeIdList = monitorWireIds.get(node);

            for (int i = 0; i < values.size(); i++) {
                String wireId = nodeIdList.get(i);
                int value = values.get(i);
                writer.write(value + wireId + "\n");
                lastWireValues.get(monitor).put(node, values);
            }

            if (node.getSize() > 1) {
                String busId = monitorBusIds.get(node);
                String binStr = computeBusBinary(values);
                writer.write("b" + binStr + " " + busId + "\n");
                lastBusValues.get(monitor).put(node, binStr);
            }
        }

        writer.write("$end\n");
        writer.flush();
    }

    public void writeSample(Player player, Monitor monitor) throws IOException {

        // Invalid monitor
        if (doesntHaveMonitor(monitor)) {
            cleanMonitor(monitor);
            return;
        }

        BufferedWriter writer = writers.get(monitor);

        int timestamp = timestamps.get(monitor) + 1;
        timestamps.put(monitor, timestamp);

        StringBuilder changes = new StringBuilder();
        boolean anyChange = false;

        ArrayList<String> nodeNames = monitor.getMonitorSources();

        HashMap<Node, ArrayList<Integer>> lastWires = lastWireValues.get(monitor);
        HashMap<Node, ArrayList<String>> nodeWireIds = wireIds.get(monitor);
        HashMap<Node, String> lastBuses = lastBusValues.get(monitor);
        HashMap<Node, String> nodeBusIds = busIds.get(monitor);

        for (String nodeName : nodeNames) {

            Node node = nodeManager.getNode(player, nodeName);
            ArrayList<Integer> currentValues = node.getCurrentLevels();

            if (lastWires.containsKey(node)) {
                ArrayList<String> ids = nodeWireIds.get(node);
                ArrayList<Integer> lastValues = lastWires.get(node);
                for (int i = 0; i < ids.size(); i++) {

                    String id = ids.get(i);
                    int current = currentValues.get(i);

                    if (current != lastValues.get(i)) {
                        changes.append(current).append(id).append('\n');
                        lastValues.set(i, current);
                        anyChange = true;
                    }
                }
            }

            if (lastBuses.containsKey(node)) {
                String id = nodeBusIds.get(node);
                String lastValue = lastBuses.get(node);
                String currentValue = computeBusBinary(currentValues);
                if (!currentValue.equals(lastValue)) {
                    changes.append('b').append(currentValue).append(' ').append(id).append('\n');
                    lastBuses.put(node, currentValue);
                    anyChange = true;
                }
            }

        }

        // Push empty update if no changes, make live viewing a bit nicer
        writer.write("#" + timestamp + "\n");
        if (anyChange) {
            writer.write(changes.toString());
        }
        writer.flush();

    }

    private String computeBusBinary(ArrayList<Integer> values) {
        StringBuilder binString = new StringBuilder();

        for (Integer value : values) {
            binString.append(value);
        }

        return binString.toString();
    }

    private void writeTclScript(Path tclPath) throws IOException {
        try (var tw = new FileWriter(tclPath.toFile())) {
            tw.write("proc reload_live {} {\n");
            tw.write("    gtkwave::reLoadFile\n");
            tw.write("    after 1500 reload_live\n");
            tw.write("}\n");
            tw.write("after 1500 reload_live\n");
        }
    }

    private void writeLaunchScript(Path scriptPath, String vcdFile, String tclFile) throws IOException {
        try (var sw = new FileWriter(scriptPath.toFile())) {
            sw.write("#!/bin/bash\n");
            sw.write("# Redbug GTKWave live viewer\n");
            sw.write("# Usage: ./redbug_live.sh [--shm]\n");
            sw.write("#   --shm  Force shmidcat mode \n\n");
            sw.write("DIR=\"$(cd \"$(dirname \"$0\")\" && pwd)\"\n");
            sw.write("VCD=\"$DIR/" + vcdFile + "\"\n");
            sw.write("TCL=\"$DIR/" + tclFile + "\"\n\n");
            sw.write("if [ \"$1\" = \"--shm\" ]; then\n");
            sw.write("    if ! command -v shmidcat &>/dev/null; then\n");
            sw.write("        echo \"[RB]: shmidcat not found. Install gtkwave package.\"\n");
            sw.write("        exit 1\n");
            sw.write("    fi\n");
            sw.write("    echo \"[RB]: Using shmidcat live mode...\"\n");
            sw.write("    tail -c +0 -f \"$VCD\" | shmidcat | gtkwave -v -I\n");
            sw.write("else\n");
            sw.write("    echo \"[RB]: Opening GTKWave with auto-reload (every 1.5s)...\"\n");
            sw.write("    echo \"[RB]: Tip: on native Linux, try --shm for true live streaming.\"\n");
            sw.write("    gtkwave \"$VCD\" -S \"$TCL\"\n");
            sw.write("fi\n");
        }
        scriptPath.toFile().setExecutable(true);
    }

}
