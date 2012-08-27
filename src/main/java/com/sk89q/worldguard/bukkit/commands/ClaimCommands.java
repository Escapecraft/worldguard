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
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.flags.InvalidFlagFormat;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ClaimType;
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
    
    
    /**
     * Claim new land.
     * <p />
     * Anyone with worldguard.claim.newland permission can claim new land.
     */
    @Command(aliases = {"newland", "land", "nl"}, usage = "<landclaim_name>",
            desc = "Defines a new landclaim", min = 1, max = 1)
    // for claiming new land
    @CommandPermissions({"worldguard.claim.newland"})
    public void define(CommandContext args, CommandSender sender) throws CommandException {
        
        Player player = plugin.checkPlayer(sender);
        LocalPlayer localPlayer = plugin.wrapPlayer(player);
        WorldEditPlugin worldEdit = plugin.getWorldEdit();
        String id = args.getString(0);
        
        if (!ProtectedRegion.isValidId(id)) {
            throw new CommandException("Invalid landclaim name!");
        }
        
        if (id.equalsIgnoreCase("__global__")) {
            throw new CommandException("A landclaim cannot be named __global__");
        }
        
        // Attempt to get the player's selection from WorldEdit
        Selection sel = worldEdit.getSelection(player);
        
        if (sel == null) {
            throw new CommandException("Select some land with the wand (wood shovel) first.");
        }
        
        RegionManager mgr = plugin.getGlobalRegionManager().get(sel.getWorld());
        if (mgr.hasRegion(id)) {
            throw new CommandException("That landclaim name already exists. Please choose a different name.  Perhaps you meant to modify an existing one?");
        }
        
        ProtectedRegion region;

        // Only allow cube-shaped cuboids
        if (sel instanceof CuboidSelection) {
            BlockVector min = sel.getNativeMinimumPoint().toBlockVector();
            BlockVector max = sel.getNativeMaximumPoint().toBlockVector();
            region = new ProtectedCuboidRegion(id, min, max);
        } else {
            throw new CommandException(
                    "You may only have rectangular/square landclaims.");
        }

        WorldConfiguration wcfg = plugin.getGlobalStateManager().get(player.getWorld());

        // Check whether the player has created too many regions
        int maxRegionCount = wcfg.getMaxRegionCount(player);
        if (maxRegionCount >= 0
                && mgr.getRegionCountOfPlayer(localPlayer) >= maxRegionCount) {
            throw new CommandException("You own too many landclaims in this world, delete one in order claim a new one.");
        }

        ProtectedRegion existing = mgr.getRegionExact(id);

        // expand region to maximum vertical size
        region.expandVert(wcfg.claimFloor, player.getWorld().getMaxHeight() - 1);

        ApplicableRegionSet regions = mgr.getApplicableRegions(region);

        // Check if this region overlaps any other region
        checkForOverlap(regions, wcfg, localPlayer);

        // check if the new region is too close to other regions
        checkForOverlapBorder(id, region, mgr, wcfg, localPlayer);

        if (region.area() > wcfg.maxClaimArea) {
            player.sendMessage(ChatColor.RED +
                    "This land area is too large to claim.");
            player.sendMessage(ChatColor.RED +
                    "Max. area: " + wcfg.maxClaimArea + ", your area: " + region.area());
        }
        
        // set player as owner
        region.getOwners().addPlayer(player.getName());

        // set claim type
        region.setClaimType(ClaimType.LAND);

        // set flags
        region.setFlag((StateFlag)getFlag("vehicle-place"), StateFlag.State.ALLOW);
        region.setFlag((StateFlag)getFlag("creeper-explosion"), StateFlag.State.DENY);
        region.setFlag((StateFlag)getFlag("ghast-fireball"), StateFlag.State.DENY);

        // save region
        mgr.addRegion(region);
        try {
            mgr.save();
            sender.sendMessage(ChatColor.YELLOW + "Landclaim saved as " + id + ".");
        } catch (ProtectionDatabaseException e) {
            throw new CommandException("Failed to write Landclaim: "
                    + e.getMessage());
        }
    }

    /**
     * Add or remove a member of owned landclaim.
     * <p />
     * Only the owner may add or remove a member of a landclaim (or region).
     */
    @Command(aliases = {"member", "members", "mem"}, usage = "<landclaim_name> <add|remove> <members...>",
            desc = "Add or remove members to an owned landclaim", min = 3)
    public void changeMember(CommandContext args, CommandSender sender) throws CommandException {
        Player player = plugin.checkPlayer(sender);
        World world = player.getWorld();
        LocalPlayer localPlayer = plugin.wrapPlayer(player);
        String id = args.getString(0);
        String subCmd = args.getString(1);
        
        if (id.equalsIgnoreCase("__global__")) {
            throw new CommandException("You may not change the __global__ landclaim.");
        }

        RegionManager mgr = plugin.getGlobalRegionManager().get(world);
        ProtectedRegion region = mgr.getRegionExact(id);

        if (region == null) {
            throw new CommandException("Could not find landclaim named: " + id);
        }

        if (!region.isOwner(localPlayer)) {
            throw new CommandException("You are not the owner of this landclaim (" + id + ").  Only the owner may add or remove members.");
        } 

        id = region.getId();

        if (!subCmd.equalsIgnoreCase("add") && !subCmd.equalsIgnoreCase("rem") && !subCmd.equalsIgnoreCase("remove")) {
            throw new CommandException("You must specify whether to add or remove members.");
        }

        if (subCmd.equalsIgnoreCase("add")) {
            RegionDBUtil.addToDomain(region.getMembers(), args.getPaddedSlice(3, 0), 0);
        }

        if (subCmd.equalsIgnoreCase("rem") || subCmd.equalsIgnoreCase("remove")) {
            RegionDBUtil.removeFromDomain(region.getMembers(), args.getPaddedSlice(3, 0), 0);
        }
        
        try {
            mgr.save();
            sender.sendMessage(ChatColor.YELLOW + "Landclaim " + id + " updated.");
        } catch (ProtectionDatabaseException e) {
            throw new CommandException("Failed to write Landclaim: "
                    + e.getMessage());
        }

    }
    
    /**
     * Change size of owned landclaim.
     * <p />
     * Only the owner may change the size of a landclaim (or region).
     */
    @Command(aliases = {"change", "update", "changesize", "expand"}, usage = "<landclaim_name>",
            desc = "Changes the size of an owned landclaim", min = 1, max = 1)
    public void change(CommandContext args, CommandSender sender) throws CommandException {
        
        Player player = plugin.checkPlayer(sender);
        World world = player.getWorld();
        WorldEditPlugin worldEdit = plugin.getWorldEdit();
        LocalPlayer localPlayer = plugin.wrapPlayer(player);
        String id = args.getString(0);
        
        if (id.equalsIgnoreCase("__global__")) {
            throw new CommandException("You may not change the __global__ landclaim.");
        }

        RegionManager mgr = plugin.getGlobalRegionManager().get(world);
        ProtectedRegion existing = mgr.getRegionExact(id);

        if (existing == null) {
            throw new CommandException("Could not find landclaim named: " + id);
        }

        // only onwers can change the size of their landclaim
        // admin/moderators should use the regular region commands
        if (!existing.isOwner(localPlayer)) {
            throw new CommandException("You are not the owner of this landclaim (" + id + ").  Only the owner may change the size.");
        } 
        
        // Attempt to get the player's selection from WorldEdit
        Selection sel = worldEdit.getSelection(player);
        
        if (sel == null) {
            throw new CommandException("Select some land with the wand (wood shovel) first.  Ideally, this should include your original landclaim area.");
        }
        
        ProtectedRegion region;
        
        // Only allow cube-shaped cuboids
        if (sel instanceof CuboidSelection) {
            BlockVector min = sel.getNativeMinimumPoint().toBlockVector();
            BlockVector max = sel.getNativeMaximumPoint().toBlockVector();
            region = new ProtectedCuboidRegion(id, min, max);
        } else {
            throw new CommandException(
                    "You may only have rectangular/square landclaims.");
        }

        WorldConfiguration wcfg = plugin.getGlobalStateManager().get(player.getWorld());

        // expand region to maximum vertical size
        region.expandVert(wcfg.claimFloor, player.getWorld().getMaxHeight() - 1);

        ApplicableRegionSet regions = mgr.getApplicableRegions(region);

        // Check if this region overlaps any other region
        checkForOverlap(regions, wcfg, localPlayer);

        // check if the new region is too close to other regions
        checkForOverlapBorder(id, region, mgr, wcfg, localPlayer);

        if (region.area() > wcfg.maxClaimArea) {
            player.sendMessage(ChatColor.RED +
                    "This land area is too large to claim.");
            player.sendMessage(ChatColor.RED +
                    "Max. area: " + wcfg.maxClaimArea + ", your area: " + region.area());
        }

        // add data from previous landclaim definition
        region.setMembers(existing.getMembers());
        region.setOwners(existing.getOwners());
        region.setFlags(existing.getFlags());
        region.setPriority(existing.getPriority());
        region.setClaimType(existing.getClaimType());
        try {
            region.setParent(existing.getParent());
        } catch (CircularInheritanceException ignore) {
        }
        
        // update landclaim
        mgr.addRegion(region);
        sender.sendMessage(ChatColor.YELLOW + "Landclaim " + region.getId() + " updated with new land area.");
        
        try {
            mgr.save();
            sender.sendMessage(ChatColor.YELLOW + "Landclaim saved as " + id + ".");
        } catch (ProtectionDatabaseException e) {
            throw new CommandException("Failed to write Landclaim: "
                    + e.getMessage());
        }
    }
    
    /**
     * Select an existing landclaim.
     * <p />
     * Anyone may select an existing landclaim (or region).
     */
    @Command(aliases = {"select", "sel", "s", "choose"}, usage = "[landclaim_name]",
            desc = "Select an existing landclaim", min = 0, max = 1)
    public void select(CommandContext args, CommandSender sender) throws CommandException {

        final Player player = plugin.checkPlayer(sender);
        final World world = player.getWorld();
        final LocalPlayer localPlayer = plugin.wrapPlayer(player);

        final RegionManager mgr = plugin.getGlobalRegionManager().get(world);
        WorldConfiguration wcfg = plugin.getGlobalStateManager().get(world);

        final String id;
        if (args.argsLength() == 0) {
            id = findRegionPlayerPos(localPlayer, wcfg, mgr);
        }
        else {
            id = args.getString(0);
        }

        final ProtectedRegion region = mgr.getRegion(id);

        if (region == null) {
            throw new CommandException("Could not find landclaim named: " + id);
        }

        selectRegion(player, localPlayer, region);
    }
    
    /**
     * Get information about an existing landclaim.
     * <p />
     * Anyone may get information about an existing landclaim.
     * Must be claim type: land.
     */
    @Command(aliases = {"info", "i"}, usage = "[world] [landclaim_name]", flags = "s",
            desc = "Get information about a landclaim", min = 0, max = 2)
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
        WorldConfiguration wcfg = plugin.getGlobalStateManager().get(world);

        final String id;

        // Get different values based on provided arguments
        switch (args.argsLength()) {
        case 0:
            if (localPlayer == null) {
                throw new CommandException("A player is expected.");
            }

            id = findRegionPlayerPos(localPlayer, wcfg, mgr);
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
                throw new CommandException("Invalid landclaim name!");
            }
            throw new CommandException("Could not find landclaim named: " + id);
        }

        displayRegionInfo(sender, localPlayer, region);

        if (args.hasFlag('s')) {
            selectRegion(plugin.checkPlayer(sender), localPlayer, region);
        }
    }

    private void displayRegionInfo(CommandSender sender, final LocalPlayer localPlayer, ProtectedRegion region) throws CommandException {
        final String id = region.getId();

        if (region.getClaimType() != ClaimType.LAND) {
            throw new CommandException("The name " + id + " does not refer to a land claim type region.");
        }

        sender.sendMessage(ChatColor.YELLOW + "Landclaim: " + id + ChatColor.GRAY + ", claim: " + region.getClaimType() + ChatColor.GRAY + ", type: " + region.getTypeName() + ", " + ChatColor.GRAY + "Priority: " + region.getPriority());

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
        sender.sendMessage(ChatColor.LIGHT_PURPLE + "Area: " + region.xLength() + " x " + region.zLength() + ", Size: " + region.area());
        sender.sendMessage(ChatColor.LIGHT_PURPLE + "Bounds:"
                + " (" + min.getBlockX() + "," + min.getBlockY() + "," + min.getBlockZ() + ")"
                + " (" + max.getBlockX() + "," + max.getBlockY() + "," + max.getBlockZ() + ")"
        );
    }

    /**
     * Get a list of landclaims the player owns.
     */
    @Command(aliases = {"list", "owned", "lo"}, usage = "[page] [world]",
            desc = "Get a list of owned landclaims", max = 2)
    public void list(CommandContext args, CommandSender sender) throws CommandException {

        String name = sender.getName().toLowerCase();
        LocalPlayer localPlayer = plugin.wrapPlayer(plugin.checkPlayer(sender));

        int page = 0;
        World world = null;

        if (args.argsLength() > 0) {
            try {
                page = Math.max(0, (args.getInteger(0) - 1));
            } catch (NumberFormatException nfe) {
                world = plugin.matchWorld(sender, args.getString(0));
            }

            if (args.argsLength() == 2) {
                if (page == 0) {
                    page = Math.max(0, args.getInteger(1) - 1);
                } else if (world == null) {
                    world = plugin.matchWorld(sender, args.getString(1));
                }
            }
        }

        if (world == null) {
            world = plugin.checkPlayer(sender).getWorld();
        }

        final RegionManager mgr = plugin.getGlobalRegionManager().get(world);
        final Map<String, ProtectedRegion> regions = mgr.getRegions();

        List<ProtectedRegion> regionsOwned = new ArrayList<ProtectedRegion>();
        for (String id : regions.keySet()) {
            ProtectedRegion region = regions.get(id);
            if ((region.getClaimType() == ClaimType.LAND) && region.isOwner(localPlayer)) { 
                regionsOwned.add(region);
            }
        }

        Collections.sort(regionsOwned);

        final int totalSize = regionsOwned.size();
        if (totalSize == 0) {
            sender.sendMessage(ChatColor.RED + "No landclaims owned in world " + world.getName());
        } else {
            final int pageSize = 10;
            final int pages = (int) Math.ceil(totalSize / (float) pageSize);

            sender.sendMessage(ChatColor.RED + "Landclaims Owned in World " + world.getName() + " (page " + (page + 1) + " of " + pages + "):");

            if (page < pages) {
                for (int i = page * pageSize; i < page * pageSize + pageSize; i++) {
                    if (i >= totalSize) {
                        break;
                    }
                    sender.sendMessage(ChatColor.YELLOW.toString() + regionsOwned.get(i).getId());
                }
            }
        }
    }
    
    @Command(aliases = {"remove", "delete", "del", "rem"}, usage = "<landclaim_name>",
            desc = "Remove an owned landclaim", min = 1, max = 1)
    public void remove(CommandContext args, CommandSender sender) throws CommandException {

        Player player = plugin.checkPlayer(sender);
        World world = player.getWorld();
        LocalPlayer localPlayer = plugin.wrapPlayer(player);
        String id = args.getString(0);
        
        if (id.equalsIgnoreCase("__global__")) {
            throw new CommandException("You may not change the __global__ landclaim.");
        }
        
        RegionManager mgr = plugin.getGlobalRegionManager().get(world);
        ProtectedRegion region = mgr.getRegionExact(id);

        if (region == null) {
            throw new CommandException("Could not find landclaim named: " + id);
        }

        if (!region.isOwner(localPlayer)) {
            throw new CommandException("You are not the owner of this landclaim (" + id + ").  Only the owner may delete the landclaim.");
        } 
        
        mgr.removeRegion(id);
        
        sender.sendMessage(ChatColor.YELLOW
                + "Landclaim '" + id + "' deleted.");
        
        try {
            mgr.save();
        } catch (ProtectionDatabaseException e) {
            throw new CommandException("Failed to write Landclaim: "
                    + e.getMessage());
        }
    }

    private void selectRegion(Player player, LocalPlayer localPlayer, ProtectedRegion region) throws CommandException {
        final WorldEditPlugin worldEdit = plugin.getWorldEdit();
        final String id = region.getId();

        final World world = player.getWorld();
        if (region instanceof ProtectedCuboidRegion) {
            final ProtectedCuboidRegion cuboid = (ProtectedCuboidRegion) region;
            final Vector pt1 = cuboid.getMinimumPoint();
            final Vector pt2 = cuboid.getMaximumPoint();
            final CuboidSelection selection = new CuboidSelection(world, pt1, pt2);
            worldEdit.setSelection(player, selection);
            player.sendMessage(ChatColor.YELLOW + "Selected " + region.getId() + " as a landclaim.");
        } else if (region instanceof GlobalProtectedRegion) {
            throw new CommandException("You may not select global regions.");
        } else {
            throw new CommandException("Unknown region type: " + region.getClass().getCanonicalName());
        }
    }

    private <V> void setFlag(ProtectedRegion region, Flag<V> flag, CommandSender sender, String value) throws InvalidFlagFormat {
        region.setFlag(flag, flag.parseInput(plugin, sender, value));
    }

    private void checkForOverlap(ApplicableRegionSet regions, WorldConfiguration wcfg, LocalPlayer localPlayer) throws CommandException {
        // Check if this region overlaps any other region
        if (regions.size() > 0) {
            if (!regions.isOwnerOfAll(localPlayer, wcfg.claimIgnoreNegPriority)) {
                Iterator<ProtectedRegion> it = regions.iterator();
                StringBuilder errText = new StringBuilder("This land overlaps with someone else's land: ");
                while (it.hasNext()) {
                    ProtectedRegion r = it.next();
                    if (!r.isOwner(localPlayer)) {
                        errText.append(r.getId());
                    }
                }
                throw new CommandException(errText.toString());
            }
        }
    }

    private void checkForOverlapBorder(String id, ProtectedRegion region, RegionManager mgr, WorldConfiguration wcfg, LocalPlayer localPlayer) throws CommandException {
        ProtectedRegion regionWithBorder = new ProtectedCuboidRegion(id + "WithBorder", region.getMinimumPoint(), region.getMaximumPoint());
        regionWithBorder.expandArea(wcfg.claimBorder);
        ApplicableRegionSet regionsWithBorder = mgr.getApplicableRegions(regionWithBorder);
        if (regionsWithBorder.size() > 0) {
            if (!regionsWithBorder.isOwnerOfAll(localPlayer, wcfg.claimIgnoreNegPriority)) {
                Iterator<ProtectedRegion> it = regionsWithBorder.iterator();
                StringBuilder errText = new StringBuilder("This land is too close to someone else's land: ");
                while (it.hasNext()) {
                    ProtectedRegion r = it.next();
                    if (!r.isOwner(localPlayer)) {
                        errText.append(r.getId());
                    }
                }
                throw new CommandException(errText.toString());
            }
        }
    }

    /**
     * Find a region where the player is located.
     *
     * @param localPlayer the local player
     * @return the region id
     */
    private String findRegionPlayerPos(LocalPlayer localPlayer, WorldConfiguration wcfg, RegionManager mgr) throws CommandException {
        final Vector pt = localPlayer.getPosition();
        final ApplicableRegionSet set = mgr.getApplicableRegions(pt);

        // get rid of any "world" cuboids
        Iterator<ProtectedRegion> it = set.iterator();
        if (wcfg.claimIgnoreNegPriority) {
            while (it.hasNext()) {
                if (it.next().getPriority() < 0) {
                    it.remove();
                }
            }
        }

        if (set.size() == 0) {
            throw new CommandException("You didn't specify a landclaim name and no landclaim exists at this location!");
        }

        // get the next region in the list
        return set.iterator().next().getId();
    }

    /**
     * Get the flag.
     * <p />
     * Since sk89q doesn't have a way to get a known flag - have to search
     * for it.
     *
     * @param flagName the flag name
     * @return the flag object
     */
    private Flag<?> getFlag(String flagName) {
        for (Flag<?> flag : DefaultFlag.getFlags()) {
            if (flag.getName().equalsIgnoreCase(flagName)) {
                return flag;
            }
        }

        return null;
    }
}
