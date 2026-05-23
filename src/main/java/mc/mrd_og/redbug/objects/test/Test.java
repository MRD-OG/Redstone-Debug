package mc.mrd_og.redbug.objects.test;

import mc.mrd_og.redbug.objects.monitor.Monitor;
import mc.mrd_og.redbug.objects.monitor.MonitorManager;
import mc.mrd_og.redbug.objects.node.Node;
import mc.mrd_og.redbug.objects.node.NodeManager;
import mc.mrd_og.redbug.objects.node.NodeType;
import mc.mrd_og.redbug.plugin.Redbug;
import mc.mrd_og.redbug.util.BinaryHelper;
import mc.mrd_og.redbug.vcd.VcdManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Test {

    private final String name;
    private final HashMap<String, List<String>> csvData;

    private final NodeManager nodeManager;
    private final MonitorManager monitorManager;
    private final VcdManager vcdManager;

    private final Redbug plugin;

    BukkitTask testTask;


    // test info
    private String monitorName, clkPinName;
    private int clkPeriod=-1, currentCycle=-1, testLength=-1;

    boolean testRunning;

    public Test(Redbug plugin, String name, HashMap<String, List<String>> csvData) {
        this.name = name;
        this.csvData = csvData;
        this.plugin = plugin;

        if (!csvData.entrySet().isEmpty()) {
            testLength = csvData.get(csvData.keySet().stream().toList().get(0)).size();
        }

        nodeManager = NodeManager.getInstance(plugin);
        monitorManager = MonitorManager.getInstance(plugin);
        vcdManager = VcdManager.getInstance();
    }

    public String getName() {
        return name;
    }

    public Map<String, List<String>> getCsvData() {
        return csvData;
    }


    class ClockTask extends BukkitRunnable {

        private final Redbug plugin;
        private int remaining;
        private int duration;
        private Player player;
        private String clkPinName;

        ClockTask(Redbug plugin, int duration, int remaining, Player player, String clkPinName) {
            this.plugin = plugin;
            this.duration = duration;
            this.remaining = remaining;
            this.player = player;
            this.clkPinName = clkPinName;
        }

        @Override
        public void run() {
            if (remaining > 0) {
                new ClockTask(plugin, duration, remaining - 1, player, clkPinName).runTaskLater(plugin, 1L);
                return;
            }

            advanceTest(player);

            if (currentCycle > 0)
                nodeManager.pinOperation(player, clkPinName, "1", false, (duration / 2));

            if (testRunning)
                new ClockTask(plugin, clkPeriod, clkPeriod, player, clkPinName).runTask(plugin);
        }
    }


    public void advanceTest(Player player) {

        currentCycle++;

        if (currentCycle == testLength) {
            stop(player);
            return;
        }

        for (Map.Entry<String, List<String>> entry : csvData.entrySet()) {

            Node node = nodeManager.getNode(player, entry.getKey());
            // todo: add a check for whether a node is currently in a test, if so, do not allow modification in /pin or /wire command


            String nodeName = entry.getKey();
            int value = Integer.parseInt(entry.getValue().get(currentCycle));
            String binary_value;

            if (value < 0) {
                binary_value = BinaryHelper.toSignedBinary(value, node.getLocations().size());
            } else {
                binary_value = BinaryHelper.toUnsignedBinary(value, node.getLocations().size());
            }


            if (node == null) {
                player.sendMessage(Component.text("[RB]: Node " + nodeName + " no longer exists try /pin add " + nodeName, NamedTextColor.RED));
                return;
            }

            if (node.getType() != NodeType.PIN) {
                player.sendMessage(Component.text("[RB]: Node " + nodeName + " must be a PIN try /pin add " + nodeName, NamedTextColor.RED));
                return;
            }

            // Set values of all pins
            nodeManager.pinOperation(player, nodeName, binary_value, false, -1);

        }

    }
    public void start(Player player, String monitorName, String clkPinName, int clkPeriod) {//, int clkOffset) {
        // todo: when started, test should account for any new pins/wire lengths
        this.currentCycle = -1;
        this.monitorName = monitorName;
        this.clkPinName = clkPinName;
        this.clkPeriod = clkPeriod;
        this.testRunning = true;

        new ClockTask(plugin, clkPeriod, clkPeriod, player, clkPinName).runTask(plugin);
    }

    public void stop(Player player) {

        testRunning = false;

        if (testTask != null) {
            testTask.cancel();
        }
        player.sendMessage(Component.text("[RB]: Test " + name + " finished", NamedTextColor.GOLD));

        vcdManager.stopDump(monitorManager.getMonitor(player, monitorName));

        player.sendMessage(Component.text("[RB]: Monitor " + monitorName + " VCD generation stopped", NamedTextColor.AQUA));
    }

    public void printInfo(Player player) {

        TextComponent message = Component.text("[RB]: Test " + this.name + " has the following info:", NamedTextColor.GOLD);

        message = message.appendNewline()
                .append(Component.text(" -> Running: ", NamedTextColor.AQUA))
                .append(Component.text(testRunning, NamedTextColor.WHITE))
                .appendNewline()
                .append(Component.text(" -> Test length (cycles): ", NamedTextColor.AQUA))
                .append(Component.text(testLength, NamedTextColor.WHITE))
                .appendNewline()
                .append(Component.text(" -> Clock pin name: ", NamedTextColor.AQUA))
                .append(Component.text(clkPinName, NamedTextColor.WHITE))
                .appendNewline()
                .append(Component.text(" -> Current cycle: ", NamedTextColor.AQUA))
                .append(Component.text(currentCycle, NamedTextColor.WHITE))
                .appendNewline()
                .append(Component.text(" -> Current monitor: ", NamedTextColor.AQUA))
                .append(Component.text(monitorName, NamedTextColor.WHITE))
                .appendNewline()
                .append(Component.text(" -> Clock period (ticks): ", NamedTextColor.AQUA))
                .append(Component.text(clkPeriod, NamedTextColor.WHITE));

        player.sendMessage(message);

    }

    public boolean isTestRunning() {
        return testRunning;
    }

    public int getClkPeriod() {
        return clkPeriod;
    }

    public int getTestLength() {
        return testLength;
    }

    public int getCurrentCycle() {
        return currentCycle;
    }

    public String getClkPinName() {
        return clkPinName;
    }

    public String getMonitorName() {
        return monitorName;
    }

    public Monitor getMonitor(Player player) {
        return monitorManager.getMonitors(player).get(getMonitorName());
    }
}
