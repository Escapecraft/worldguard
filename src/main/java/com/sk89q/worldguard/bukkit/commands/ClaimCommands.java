// $Id$
/*
 * WorldGuard
 * Copyright (C) 2010 sk89q <http://www.sk89q.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
*/

package com.sk89q.worldguard.bukkit.commands;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.sk89q.minecraft.util.commands.Command;
import com.sk89q.minecraft.util.commands.CommandContext;
import com.sk89q.minecraft.util.commands.CommandException;
import com.sk89q.minecraft.util.commands.CommandPermissions;
import com.sk89q.minecraft.util.commands.CommandPermissionsException;
import com.sk89q.worldedit.BlockVector;
import com.sk89q.worldedit.Location;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.bukkit.BukkitUtil;
import com.sk89q.worldedit.bukkit.WorldEditPlugin;
import com.sk89q.worldedit.bukkit.selections.CuboidSelection;
import com.sk89q.worldedit.bukkit.selections.Selection;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.bukkit.WorldConfiguration;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.domains.DefaultDomain;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.flags.DefaultFlag;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.InvalidFlagFormat;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.GlobalProtectedRegion;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion.CircularInheritanceException;
import com.sk89q.worldguard.protection.databases.ProtectionDatabaseException;
import com.sk89q.worldguard.protection.databases.RegionDBUtil;

/**
 * Modified for Escapecraft, from RegionCommands class.
 * @author Modifications by Tulonsae
 */
public class ClaimCommands {
    private final WorldGuardPlugin plugin;

    public ClaimCommands(WorldGuardPlugin plugin) {
        this.plugin = plugin;
    }
    
    @Command(aliases = {"new", "n"}, usage = "<cuboid_name>",
            desc = "Defines a new cuboid", min = 1, max = 1)
    @CommandPermissions({"worldguard.claim.new"})
    public void define(CommandContext args, CommandSender sender) throws CommandException {
        
        Player player = plugin.checkPlayer(sender);
        LocalPlayer localPlayer = plugin.wrapPlayer(player);
        WorldEditPlugin worldEdit = plugin.getWorldEdit();
        String id = args.getString(0);
        
        if (!ProtectedRegion.isValidId(id)) {
            throw new CommandException("Invalid cuboid name!");
        }
        
        if (id.equalsIgnoreCase("__global__")) {
            throw new CommandException("A cuboid cannot be named __global__");
        }
        
        // Attempt to get the player's selection from WorldEdit
        Selection sel = worldEdit.getSelection(player);
        
        if (sel == null) {
            throw new CommandException("Select a cuboid with the wand first.");
        }
        
        RegionManager mgr = plugin.getGlobalRegionManager().get(sel.getWorld());
        if (mgr.hasRegion(id)) {
            throw new CommandException("That cuboid already exists. Please choose a different name (or use change to modify the existing one).");
        }
        
        ProtectedRegion region;

        // Only allow cube-shaped cuboids
        if (sel instanceof CuboidSelection) {
            BlockVector min = sel.getNativeMinimumPoint().toBlockVector();
            BlockVector max = sel.getNativeMaximumPoint().toBlockVector();
            region = new ProtectedCuboidRegion(id, min, max);
        } else {
            throw new CommandException(
                    "You may only use cubish shaped cuboids.");
        }

        WorldConfiguration wcfg = plugin.getGlobalStateManager().get(player.getWorld());

        if (!plugin.hasPermission(sender, "worldguard.region.unlimited")) {
            // Check whether the player has created too many regions
            int maxRegionCount = wcfg.getMaxRegionCount(player);
            if (maxRegionCount >= 0
                    && mgr.getRegionCountOfPlayer(localPlayer) >= maxRegionCount) {
                throw new CommandException("You own too many cuboids in this world, delete one first to claim a new one.");
            }
        }

        ProtectedRegion existing = mgr.getRegionExact(id);

        // Check for an existing region
        // shouldn't need to do this since we checked earlier - Tulon
        if (existing != null) {
            throw new CommandException("This cuboid already exists.  Please choose a different name.");
        }

        // expand region to maximum veritcal size
        region.expandVert(wcfg.claimFloor, player.getWorld().getMaxHeight() - 1);

        ApplicableRegionSet regions = mgr.getApplicableRegions(region);

        // Check if this region overlaps any other region
        if (regions.size() > 0) {
            if (!regions.isOwnerOfAll(localPlayer, wcfg.claimIgnoreNegPriority)) {
                Iterator<ProtectedRegion> it = regions.iterator();
                StringBuilder errText = new StringBuilder("This cuboid overlaps with someone else's cuboid: ");
                while (it.hasNext()) {
                    ProtectedRegion r = it.next();
                    if (!r.isOwner(localPlayer)) {
                        errText.append(r.getId());
                    }
                }
                throw new CommandException(errText.toString());
            }
        }

        // check if the new region is too close to other regions
        ProtectedRegion regionWithBorder = new ProtectedCuboidRegion(id + "WithBorder", region.getMinimumPoint(), region.getMaximumPoint());
        regionWithBorder.expandArea(wcfg.claimBorder);
        ApplicableRegionSet regionsWithBorder = mgr.getApplicableRegions(regionWithBorder);
        if (regionsWithBorder.size() > 0) {
            if (!regionsWithBorder.isOwnerOfAll(localPlayer, wcfg.claimIgnoreNegPriority)) {
                Iterator<ProtectedRegion> it = regionsWithBorder.iterator();
                StringBuilder errText = new StringBuilder("This cuboid is too close to someone else's cuboid: ");
                while (it.hasNext()) {
                    ProtectedRegion r = it.next();
                    if (!r.isOwner(localPlayer)) {
                        errText.append(r.getId());
                    }
                }
                throw new CommandException(errText.toString());
            }
        }

        if (!plugin.hasPermission(sender, "worldguard.region.unlimited")) {
            if (region.area() > wcfg.maxClaimArea) {
                player.sendMessage(ChatColor.RED +
                        "This cuboid is too large to claim.");
                player.sendMessage(ChatColor.RED +
                        "Max. area: " + wcfg.maxClaimArea + ", your area: " + region.area());
            }
        }
        
        region.getOwners().addPlayer(player.getName());
        mgr.addRegion(region);

        try {
            mgr.save();
            sender.sendMessage(ChatColor.YELLOW + "Cuboid saved as " + id + ".");
        } catch (ProtectionDatabaseException e) {
            throw new CommandException("Failed to write cuboid: "
                    + e.getMessage());
        }
    }
    
    @Command(aliases = {"change", "update", "expand", "contract"}, usage = "<id>",
            desc = "Changes the size of a cuboid", min = 1, max = 1)
    public void change(CommandContext args, CommandSender sender) throws CommandException {
        
        Player player = plugin.checkPlayer(sender);
        World world = player.getWorld();
        WorldEditPlugin worldEdit = plugin.getWorldEdit();
        LocalPlayer localPlayer = plugin.wrapPlayer(player);
        String id = args.getString(0);
        
        if (id.equalsIgnoreCase("__global__")) {
            throw new CommandException("You may not change the __global__ cuboid.");
        }

        RegionManager mgr = plugin.getGlobalRegionManager().get(world);
        ProtectedRegion existing = mgr.getRegionExact(id);

        if (existing == null) {
            throw new CommandException("Could not find a cuboid named: " + id);
        }

        if (existing.isOwner(localPlayer)) {
            plugin.checkPermission(sender, "worldguard.claim.change.own");
        } else if (existing.isMember(localPlayer)) {
            plugin.checkPermission(sender, "worldguard.claim.change.member");
        } else {
            plugin.checkPermission(sender, "worldguard.claim.change");
        } 
        
        // Attempt to get the player's selection from WorldEdit
        Selection sel = worldEdit.getSelection(player);
        
        if (sel == null) { // TODO
            throw new CommandException("First, select a cuboid with the wand or the select command.");
        }
        
        ProtectedRegion region;
        
        // Only allow cube-shaped cuboids
        if (sel instanceof CuboidSelection) {
            BlockVector min = sel.getNativeMinimumPoint().toBlockVector();
            BlockVector max = sel.getNativeMaximumPoint().toBlockVector();
            region = new ProtectedCuboidRegion(id, min, max);
        } else {
            throw new CommandException(
                    "You may only use cubish shaped cuboids.");
        }

        region.setMembers(existing.getMembers());
        region.setOwners(existing.getOwners());
        region.setFlags(existing.getFlags());
        region.setPriority(existing.getPriority());
        try {
            region.setParent(existing.getParent());
        } catch (CircularInheritanceException ignore) {
        }
        
        mgr.addRegion(region);
        
        sender.sendMessage(ChatColor.YELLOW + "Cuboid " + region.getId() + " updated with new area.");
        
        try {
            mgr.save();
        } catch (ProtectionDatabaseException e) {
            throw new CommandException("Failed to write cuboid: "
                    + e.getMessage());
        }
    }
    
    @Command(aliases = {"select", "sel", "s"}, usage = "[id]",
            desc = "Select a cuboid", min = 0, max = 1)
    public void select(CommandContext args, CommandSender sender) throws CommandException {

        final Player player = plugin.checkPlayer(sender);
        final World world = player.getWorld();
        final LocalPlayer localPlayer = plugin.wrapPlayer(player);

        final RegionManager mgr = plugin.getGlobalRegionManager().get(world);

        final String id;
        if (args.argsLength() == 0) {
            final Vector pt = localPlayer.getPosition();
            final ApplicableRegionSet set = mgr.getApplicableRegions(pt);
            if (set.size() == 0) {
                throw new CommandException("You didn't specify a cuboid name and no region exists at this location!");
            }

            id = set.iterator().next().getId();
        }
        else {
            id = args.getString(0);
        }

        final ProtectedRegion region = mgr.getRegion(id);

        if (region == null) {
            throw new CommandException("Could not find a cuboid with that name.");
        }

        selectRegion(player, localPlayer, region);
    }

    public void selectRegion(Player player, LocalPlayer localPlayer, ProtectedRegion region) throws CommandException, CommandPermissionsException {
        final WorldEditPlugin worldEdit = plugin.getWorldEdit();
        final String id = region.getId();

        if (region.isOwner(localPlayer)) {
            plugin.checkPermission(player, "worldguard.claim.select.own." + id.toLowerCase());
        } else if (region.isMember(localPlayer)) {
            plugin.checkPermission(player, "worldguard.claim.select.member." + id.toLowerCase());
        } else {
            plugin.checkPermission(player, "worldguard.claim.select." + id.toLowerCase());
        }

        final World world = player.getWorld();
        if (region instanceof ProtectedCuboidRegion) {
            final ProtectedCuboidRegion cuboid = (ProtectedCuboidRegion) region;
            final Vector pt1 = cuboid.getMinimumPoint();
            final Vector pt2 = cuboid.getMaximumPoint();
            final CuboidSelection selection = new CuboidSelection(world, pt1, pt2);
            worldEdit.setSelection(player, selection);
            player.sendMessage(ChatColor.YELLOW + "Selected " + region.getId() + " as a cuboid.");
        } else if (region instanceof GlobalProtectedRegion) {
            throw new CommandException("You may not select global regions.");
        } else {
            throw new CommandException("Unknown region type: " + region.getClass().getCanonicalName());
        }
    }

    @Command(aliases = {"info", "i"}, usage = "[world] [id]", flags = "s",
            desc = "Get information about a cuboid", min = 0, max = 2)
    public void info(CommandContext args, CommandSender sender) throws CommandException {

        final LocalPlayer localPlayer;
        final World world;
        if (sender instanceof Player) {
            final Player player = (Player) sender;
            localPlayer = plugin.wrapPlayer(player);
            world = player.getWorld();
        } else if (args.argsLength() < 2) {
            throw new CommandException("A player is expected.");
        } else {
            localPlayer = null;
            world = plugin.matchWorld(sender, args.getString(0));
        }

        final RegionManager mgr = plugin.getGlobalRegionManager().get(world);

        final String id;

        // Get different values based on provided arguments
        switch (args.argsLength()) {
        case 0:
            if (localPlayer == null) {
                throw new CommandException("A player is expected.");
            }

            final Vector pt = localPlayer.getPosition();
            final ApplicableRegionSet set = mgr.getApplicableRegions(pt);
            if (set.size() == 0) {
                throw new CommandException("No region ID specified and no region found at current location!");
            }

            id = set.iterator().next().getId();
            break;

        case 1:
            id = args.getString(0).toLowerCase();
            break;

        default:
            id = args.getString(1).toLowerCase();
        }

        final ProtectedRegion region = mgr.getRegion(id);

        if (region == null) {
            if (!ProtectedRegion.isValidId(id)) {
                throw new CommandException("Invalid region ID specified!");
            }
            throw new CommandException("A region with ID '" + id + "' doesn't exist.");
        }

        displayRegionInfo(sender, localPlayer, region);

        if (args.hasFlag('s')) {
            selectRegion(plugin.checkPlayer(sender), localPlayer, region);
        }
    }

    public void displayRegionInfo(CommandSender sender, final LocalPlayer localPlayer, ProtectedRegion region) throws CommandPermissionsException {
        if (localPlayer == null) {
            plugin.checkPermission(sender, "worldguard.region.info");
        } else if (region.isOwner(localPlayer)) {
            plugin.checkPermission(sender, "worldguard.region.info.own");
        } else if (region.isMember(localPlayer)) {
            plugin.checkPermission(sender, "worldguard.region.info.member");
        } else {
            plugin.checkPermission(sender, "worldguard.region.info");
        }

        final String id = region.getId();

        sender.sendMessage(ChatColor.YELLOW + "Region: " + id + ChatColor.GRAY + ", type: " + region.getTypeName() + ", " + ChatColor.BLUE + "Priority: " + region.getPriority());

        boolean hasFlags = false;
        final StringBuilder s = new StringBuilder(ChatColor.BLUE + "Flags: ");
        for (Flag<?> flag : DefaultFlag.getFlags()) {
            Object val = region.getFlag(flag);

            if (val == null) {
                continue;
            }

            if (s.length() > 0) {
                s.append(", ");
            }

            s.append(flag.getName() + ": " + String.valueOf(val));
            hasFlags = true;
        }
        if (hasFlags) {
            sender.sendMessage(s.toString());
        }

        if (region.getParent() != null) {
            sender.sendMessage(ChatColor.BLUE + "Parent: " + region.getParent().getId());
        }

        final DefaultDomain owners = region.getOwners();
        if (owners.size() != 0) {
            sender.sendMessage(ChatColor.LIGHT_PURPLE + "Owners: " + owners.toUserFriendlyString());
        }

        final DefaultDomain members = region.getMembers();
        if (members.size() != 0) {
            sender.sendMessage(ChatColor.LIGHT_PURPLE + "Members: " + members.toUserFriendlyString());
        }

        final BlockVector min = region.getMinimumPoint();
        final BlockVector max = region.getMaximumPoint();
        sender.sendMessage(ChatColor.LIGHT_PURPLE + "Bounds:"
                + " (" + min.getBlockX() + "," + min.getBlockY() + "," + min.getBlockZ() + ")"
                + " (" + max.getBlockX() + "," + max.getBlockY() + "," + max.getBlockZ() + ")"
        );
    }

    public class RegionEntry implements Comparable<RegionEntry>{
        private final String id;
        private final int index;
        private boolean isOwner;
        private boolean isMember;

        public RegionEntry(String id, int index) {
            this.id = id;
            this.index = index;
        }

        @Override
        public int compareTo(RegionEntry o) {
            if (isOwner != o.isOwner) {
                return isOwner ? 1 : -1;
            }
            if (isMember != o.isMember) {
                return isMember ? 1 : -1;
            }
            return id.compareTo(o.id);
        }

        @Override
        public String toString() {
            if (isOwner) {
                return (index + 1) + ". +" + id;
            } else if (isMember) {
                return (index + 1) + ". -" + id;
            } else {
                return (index + 1) + ". " + id;
            }
        }
    }

    @Command(aliases = {"list"}, usage = "[.player] [page] [world]",
            desc = "Get a list of regions", max = 3)
    //@CommandPermissions({"worldguard.region.list"})
    public void list(CommandContext args, CommandSender sender) throws CommandException {

        World world;
        int page = 0;
        int argOffset = 0;
        String name = "";
        boolean own = false;
        LocalPlayer localPlayer = null;

        final String senderName = sender.getName().toLowerCase();
        if (args.argsLength() > 0 && args.getString(0).startsWith(".")) {
            name = args.getString(0).substring(1).toLowerCase();
            argOffset = 1;

            if (name.equals("me") || name.isEmpty() || name.equals(senderName)) {
                own = true;
            }
        }

        // Make /rg list default to "own" mode if the "worldguard.region.list" permission is not given
        if (!own && !plugin.hasPermission(sender, "worldguard.region.list")) {
            own = true;
        }

        if (own) {
            plugin.checkPermission(sender, "worldguard.region.list.own");
            name = senderName;
            localPlayer = plugin.wrapPlayer(plugin.checkPlayer(sender));
        }

        if (args.argsLength() > argOffset) {
            page = Math.max(0, args.getInteger(argOffset) - 1);
        }

        if (args.argsLength() > 1 + argOffset) {
            world = plugin.matchWorld(sender, args.getString(1 + argOffset));
        } else {
            world = plugin.checkPlayer(sender).getWorld();
        }

        final RegionManager mgr = plugin.getGlobalRegionManager().get(world);
        final Map<String, ProtectedRegion> regions = mgr.getRegions();

        List<RegionEntry> regionEntries = new ArrayList<RegionEntry>();
        int index = 0;
        for (String id : regions.keySet()) {
            RegionEntry entry = new RegionEntry(id, index++);
            if (!name.isEmpty()) {
                if (own) {
                    entry.isOwner = regions.get(id).isOwner(localPlayer);
                    entry.isMember = regions.get(id).isMember(localPlayer);
                }
                else {
                    entry.isOwner = regions.get(id).isOwner(name);
                    entry.isMember = regions.get(id).isMember(name);
                }

                if (!entry.isOwner && !entry.isMember) {
                    continue;
                }
            }

            regionEntries.add(entry);
        }

        Collections.sort(regionEntries);

        final int totalSize = regionEntries.size();
        final int pageSize = 10;
        final int pages = (int) Math.ceil(totalSize / (float) pageSize);

        sender.sendMessage(ChatColor.RED
                + (name.equals("") ? "Regions (page " : "Regions for " + name + " (page ")
                + (page + 1) + " of " + pages + "):");

        if (page < pages) {
            for (int i = page * pageSize; i < page * pageSize + pageSize; i++) {
                if (i >= totalSize) {
                    break;
                }
                sender.sendMessage(ChatColor.YELLOW.toString() + regionEntries.get(i));
            }
        }
    }

    @Command(aliases = {"flag", "f"}, usage = "<id> <flag> [-g group] [value]", flags = "g:",
            desc = "Set flags", min = 2)
    public void flag(CommandContext args, CommandSender sender) throws CommandException {
        
        Player player = plugin.checkPlayer(sender);
        World world = player.getWorld();
        LocalPlayer localPlayer = plugin.wrapPlayer(player);
        
        String id = args.getString(0);
        String flagName = args.getString(1);
        String value = null;

        if (args.argsLength() >= 3) {
            value = args.getJoinedStrings(2);
        }

        RegionManager mgr = plugin.getGlobalRegionManager().get(world);
        ProtectedRegion region = mgr.getRegion(id);

        if (region == null) {
            if (id.equalsIgnoreCase("__global__")) {
                region = new GlobalProtectedRegion(id);
                mgr.addRegion(region);
            } else {
                throw new CommandException("Could not find a region by that ID.");
            }
        }

        // @TODO deprecate "flag.[own./member./blank]"
        boolean hasPerm = false;
        if (region.isOwner(localPlayer)) {
            if (plugin.hasPermission(sender, "worldguard.region.flag.own." + id.toLowerCase())) hasPerm = true;
            else if (plugin.hasPermission(sender, "worldguard.region.flag.regions.own." + id.toLowerCase())) hasPerm = true;
        } else if (region.isMember(localPlayer)) {
            if (plugin.hasPermission(sender, "worldguard.region.flag.member." + id.toLowerCase())) hasPerm = true;
            else if (plugin.hasPermission(sender, "worldguard.region.flag.regions.member." + id.toLowerCase())) hasPerm = true;
        } else {
            if (plugin.hasPermission(sender, "worldguard.region.flag." + id.toLowerCase())) hasPerm = true;
            else if (plugin.hasPermission(sender, "worldguard.region.flag.regions." + id.toLowerCase())) hasPerm = true;
        }
        if (!hasPerm) throw new CommandPermissionsException();
        
        Flag<?> foundFlag = null;
        
        // Now time to find the flag!
        for (Flag<?> flag : DefaultFlag.getFlags()) {
            // Try to detect the flag
            if (flag.getName().replace("-", "").equalsIgnoreCase(flagName.replace("-", ""))) {
                foundFlag = flag;
                break;
            }
        }
        
        if (foundFlag == null) {
            StringBuilder list = new StringBuilder();
            
            // Need to build a list
            for (Flag<?> flag : DefaultFlag.getFlags()) {
                if (list.length() > 0) {
                    list.append(", ");
                }

                // @TODO deprecate inconsistant "owner" permission
                if (region.isOwner(localPlayer)) {
                    if (!plugin.hasPermission(sender, "worldguard.region.flag.flags."
                            + flag.getName() + ".owner." + id.toLowerCase())
                            && !plugin.hasPermission(sender, "worldguard.region.flag.flags."
                                    + flag.getName() + ".own." + id.toLowerCase())) {
                        continue;
                    }
                } else if (region.isMember(localPlayer)) {
                    if (!plugin.hasPermission(sender, "worldguard.region.flag.flags."
                            + flag.getName() + ".member." + id.toLowerCase())) {
                        continue;
                    }
                } else {
                    if (!plugin.hasPermission(sender, "worldguard.region.flag.flags."
                                + flag.getName() + "." + id.toLowerCase())) {
                        continue;
                    }
                } 
                
                list.append(flag.getName());
            }

            player.sendMessage(ChatColor.RED + "Unknown flag specified: " + flagName);
            player.sendMessage(ChatColor.RED + "Available flags: " + list);
            return;
        }

        if (region.isOwner(localPlayer)) {
            plugin.checkPermission(sender, "worldguard.region.flag.flags."
                    + foundFlag.getName() + ".owner." + id.toLowerCase());
        } else if (region.isMember(localPlayer)) {
            plugin.checkPermission(sender, "worldguard.region.flag.flags."
                    + foundFlag.getName() + ".member." + id.toLowerCase());
        } else {
            plugin.checkPermission(sender, "worldguard.region.flag.flags."
                    + foundFlag.getName() + "." + id.toLowerCase());
        }

        if (args.hasFlag('g')) {
            String group = args.getFlag('g');
            if (foundFlag.getRegionGroupFlag() == null) {
                throw new CommandException("Region flag '" + foundFlag.getName()
                        + "' does not have a group flag!");
            }

            try {
                setFlag(region, foundFlag.getRegionGroupFlag(), sender, group);
            } catch (InvalidFlagFormat e) {
                throw new CommandException(e.getMessage());
            }

            sender.sendMessage(ChatColor.YELLOW
                    + "Region group flag for '" + foundFlag.getName() + "' set.");
        } else {
            if (value != null) {
                try {
                    setFlag(region, foundFlag, sender, value);
                } catch (InvalidFlagFormat e) {
                    throw new CommandException(e.getMessage());
                }

                sender.sendMessage(ChatColor.YELLOW
                        + "Region flag '" + foundFlag.getName() + "' set.");
            } else {
                // Clear the flag
                region.setFlag(foundFlag, null);

                sender.sendMessage(ChatColor.YELLOW
                        + "Region flag '" + foundFlag.getName() + "' cleared.");
            }
        }
        
        try {
            mgr.save();
        } catch (ProtectionDatabaseException e) {
            throw new CommandException("Failed to write regions: "
                    + e.getMessage());
        }
    }
    
    public <V> void setFlag(ProtectedRegion region,
            Flag<V> flag, CommandSender sender, String value)
                throws InvalidFlagFormat {
        region.setFlag(flag, flag.parseInput(plugin, sender, value));
    }
    
    @Command(aliases = {"remove", "delete", "del", "rem"}, usage = "<id>",
            desc = "Remove a region", min = 1, max = 1)
    public void remove(CommandContext args, CommandSender sender) throws CommandException {
        
        Player player = plugin.checkPlayer(sender);
        World world = player.getWorld();
        LocalPlayer localPlayer = plugin.wrapPlayer(player);
        
        String id = args.getString(0);

        RegionManager mgr = plugin.getGlobalRegionManager().get(world);
        ProtectedRegion region = mgr.getRegionExact(id);

        if (region == null) {
            throw new CommandException("Could not find a region by that ID.");
        }
        
        if (region.isOwner(localPlayer)) {
            plugin.checkPermission(sender, "worldguard.region.remove.own." + id.toLowerCase());
        } else if (region.isMember(localPlayer)) {
            plugin.checkPermission(sender, "worldguard.region.remove.member." + id.toLowerCase());
        } else {
            plugin.checkPermission(sender, "worldguard.region.remove." + id.toLowerCase());
        }
        
        mgr.removeRegion(id);
        
        sender.sendMessage(ChatColor.YELLOW
                + "Region '" + id + "' removed.");
        
        try {
            mgr.save();
        } catch (ProtectionDatabaseException e) {
            throw new CommandException("Failed to write regions: "
                    + e.getMessage());
        }
    }
}
