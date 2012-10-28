package org.wargamer2010.signshop.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.block.Sign;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.Location;
import org.bukkit.event.block.Action;
import org.bukkit.World;

import java.util.*;

import org.bukkit.entity.EntityType;
import org.wargamer2010.signshop.Seller;
import org.wargamer2010.signshop.SignShop;
import org.wargamer2010.signshop.configuration.SignShopConfig;
import org.wargamer2010.signshop.player.SignShopPlayer;

import org.wargamer2010.signshop.operations.SignShopOperation;
import org.wargamer2010.signshop.operations.SignShopArguments;
import org.wargamer2010.signshop.util.*;
import org.wargamer2010.signshop.specialops.SignShopSpecialOp;

public class SignShopPlayerListener implements Listener {

    private Boolean runSpecialOperations(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Set<Location> lClicked = signshopUtil.getKeysByValue(clicks.mClicksPerLocation, player);
        Boolean ranSomething = false;

        List<SignShopSpecialOp> specialops = signshopUtil.getSignShopSpecialOps();
        List<Block> clickedBlocks = new LinkedList<Block>();
        for(Location lTemp : lClicked)
            clickedBlocks.add(player.getWorld().getBlockAt(lTemp));
        if(!specialops.isEmpty()) {
            for(SignShopSpecialOp special : specialops) {
                ranSomething = (special.runOperation(clickedBlocks, event) ? true : ranSomething);
            }
            if(ranSomething)
                clicks.removePlayerFromClickmap(player);
        }

        return ranSomething;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerVillagerTrade(PlayerInteractEntityEvent event) {
        if(event.getPlayer() == null || event.getRightClicked() == null)
            return;
        Entity ent = event.getRightClicked();
        SignShopPlayer ssPlayer = new SignShopPlayer(event.getPlayer());
        if(SignShopConfig.getPreventVillagerTrade() && ent.getType() == EntityType.VILLAGER) {
            if(!event.isCancelled()) {
                ssPlayer.sendMessage(SignShopConfig.getError("villager_trading_disabled", null));
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if(!(event.getDamager().getType() == EntityType.PLAYER))
            return;
        Player player = (Player)event.getDamager();
        if(player.getItemInHand().getType() != Material.getMaterial("REDSTONE"))
            return;
        SignShopPlayer ssPlayer = new SignShopPlayer(player);
        if(event.getEntity().getType() == EntityType.PLAYER) {
           Player clickedPlayer = (Player)event.getEntity();
            if(clicks.mClicksPerPlayername.containsKey(clickedPlayer.getName())) {
                ssPlayer.sendMessage("You have deselected a player with name: " + clickedPlayer.getName());
                clicks.mClicksPerPlayername.remove(clickedPlayer.getName());
            } else {
                ssPlayer.sendMessage("You hit a player with name: " + clickedPlayer.getName());
                clicks.mClicksPerPlayername.put(clickedPlayer.getName(), player);
            }
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        // Respect protection plugins
        if(event.getClickedBlock() == null
        || event.isCancelled()
        || event.getPlayer() == null) {
            return;
        }
        // Initialize needed variables
        Block bClicked = event.getClickedBlock();
        Player player = event.getPlayer();
        SignShopPlayer ssPlayer = new SignShopPlayer(player);
        String[] sLines;
        String sOperation;
        World world = player.getWorld();
        Seller seller = SignShop.Storage.getSeller(event.getClickedBlock().getLocation());
        if(event.getAction() == Action.LEFT_CLICK_BLOCK && event.getItem() != null && seller == null && (event.getItem().getType() == Material.getMaterial("REDSTONE") || event.getItem().getType() == Material.getMaterial("INK_SACK"))) {
            if(itemUtil.clickedSign(bClicked) && event.getItem().getType() == Material.getMaterial("REDSTONE")) {
                sLines = ((Sign) bClicked.getState()).getLines();
                sOperation = signshopUtil.getOperation(sLines[0]);
                if(SignShopConfig.getBlocks(sOperation).isEmpty()) {
                    if(!runSpecialOperations(event) && !signshopUtil.registerClickedMaterial(event))
                        ssPlayer.sendMessage(SignShopConfig.getError("invalid_operation", null));
                    return;
                }

                List<String> operation = SignShopConfig.getBlocks(sOperation);
                if(!operation.contains("playerIsOp") && !ssPlayer.hasPerm(("SignShop.Signs."+sOperation), false) && !ssPlayer.hasPerm(("SignShop.Signs.*"), false)) {
                    ssPlayer.sendMessage(SignShopConfig.getError("no_permission", null));
                    return;
                }

                int iLimit = ssPlayer.reachedMaxShops();
                if(!operation.contains("playerIsOp") && iLimit > 0) {
                    ssPlayer.sendMessage(SignShopConfig.getError("too_many_shops", null).replace("!max", Integer.toString(iLimit)));
                    itemUtil.setSignStatus(bClicked, ChatColor.BLACK);
                    return;
                }

                if(SignShopConfig.getEnablePermits() && !ssPlayer.hasPerm("SignShop.Permit", true)) {
                    ssPlayer.sendMessage(SignShopConfig.getError("need_permit", null));
                    return;
                }

                Map<SignShopOperation, List<String>> SignShopOperations = signshopUtil.getSignShopOps(operation);
                if(SignShopOperations == null) {
                    ssPlayer.sendMessage(SignShopConfig.getError("invalid_operation", null));
                    return;
                }

                List<Block> containables = new LinkedList<Block>();
                List<Block> activatables = new LinkedList<Block>();
                Boolean wentOK = signshopUtil.getSignshopBlocksFromList(ssPlayer, containables, activatables, bClicked);
                if (!wentOK) {
                    return;
                }

                SignShopArguments ssArgs = new SignShopArguments(economyUtil.parsePrice(sLines[3]), null, containables, activatables,
                        ssPlayer, ssPlayer, bClicked, sOperation, event.getBlockFace());

                for(Block bCheckme : containables) {
                     if(bClicked.getWorld().getName().equals(bCheckme.getWorld().getName())) {
                        if(!signshopUtil.checkDistance(bClicked, bCheckme, SignShopConfig.getMaxSellDistance()) && !operation.contains("playerIsOp")) {
                            ssArgs.messageParts.put("!max", Integer.toString(SignShopConfig.getMaxSellDistance()));
                            ssPlayer.sendMessage(SignShopConfig.getError("too_far", ssArgs.messageParts));
                            itemUtil.setSignStatus(bClicked, ChatColor.BLACK);
                            return;
                        }
                    }
                }

                Boolean bSetupOK = false;
                for(Map.Entry<SignShopOperation, List<String>> ssOperation : SignShopOperations.entrySet()) {
                    ssArgs.set_operationParameters(ssOperation.getValue());
                    bSetupOK = ssOperation.getKey().setupOperation(ssArgs);
                    if(!bSetupOK)
                        return;
                }
                if(!bSetupOK)
                    return;
                ItemStack blacklisted = SignShopConfig.isAnyItemOnBlacklist(ssArgs.get_isItems(), ssArgs);
                if(blacklisted != null) {
                    ssArgs.messageParts.put("!blacklisted_item", itemUtil.formatData(blacklisted.getData(), blacklisted.getDurability()));
                    ssPlayer.sendMessage(SignShopConfig.getError("item_on_blacklist", ssArgs.messageParts));
                    return;
                }

                ssArgs.setMessagePart("!customer", ssPlayer.getName());
                ssArgs.setMessagePart("!owner", player.getName());
                ssArgs.setMessagePart("!player", ssPlayer.getName());
                ssArgs.setMessagePart("!world", ssPlayer.getPlayer().getWorld().getName());
                if(ssArgs.get_isItems() == null)
                    ssArgs.set_isItems(new CraftItemStack[]{new CraftItemStack(Material.getMaterial("DIRT"),1)});
                SignShop.Storage.addSeller(player.getName(), world.getName(), ssArgs.get_bSign(), ssArgs.get_containables_root(), ssArgs.get_activatables_root(), ssArgs.get_isItems(), ssArgs.miscSettings);
                if(!ssArgs.bDoNotClearClickmap)
                    clicks.removePlayerFromClickmap(player);
                ssPlayer.sendMessage(SignShopConfig.getMessage("setup", ssArgs.get_sOperation(), ssArgs.messageParts));
                itemUtil.setSignStatus(bClicked, ChatColor.DARK_BLUE);
                return;
            }
            signshopUtil.registerClickedMaterial(event);
        } else if(itemUtil.clickedSign(bClicked) && seller != null && (event.getItem() == null || (event.getItem().getType() != Material.getMaterial("INK_SACK") && event.getItem().getType() != Material.getMaterial("REDSTONE")))) {
            SignShopPlayer ssOwner = new SignShopPlayer(seller.getOwner());
            sLines = ((Sign) bClicked.getState()).getLines();
            sOperation = signshopUtil.getOperation(sLines[0]);

            // Verify the operation
            if(SignShopConfig.getBlocks(sOperation).isEmpty()){
                return;
            }

            if(ssPlayer.hasPerm(("SignShop.DenyUse."+sOperation), false) && !ssPlayer.hasPerm(("SignShop.Signs."+sOperation), false) && !ssPlayer.hasPerm(("SignShop.Admin."+sOperation), true)) {
                ssPlayer.sendMessage(SignShopConfig.getError("no_permission_use", null));
                return;
            }

            List<String> operation = SignShopConfig.getBlocks(sOperation);

            if(!operation.contains("playerIsOp") && SignShopConfig.getEnablePermits() && !ssOwner.hasPerm("SignShop.Permit", ssPlayer.getWorld(), true)) {
                ssPlayer.sendMessage(SignShopConfig.getError("no_permit_owner", null));
                return;
            }

            if(signshopUtil.restrictedFromUsing(seller, ssPlayer)) {
                ssPlayer.sendMessage(SignShopConfig.getError("restricted_from_using", null));
                return;
            }

            Map<SignShopOperation, List<String>> SignShopOperations = signshopUtil.getSignShopOps(operation);
            if(SignShopOperations == null) {
                ssPlayer.sendMessage(SignShopConfig.getError("invalid_operation", null));
                return;
            }

            for(Block bContainable : seller.getContainables())
                if(!bContainable.getLocation().getChunk().isLoaded())
                    bContainable.getLocation().getChunk().load();
            for(Block bActivatable : seller.getActivatables())
                if(!bActivatable.getLocation().getChunk().isLoaded())
                    bActivatable.getLocation().getChunk().load();

            if(event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getItem() != null){
                event.setCancelled(true);
            }
            SignShopArguments ssArgs = new SignShopArguments(economyUtil.parsePrice(sLines[3]), seller.getItems(), seller.getContainables(), seller.getActivatables(),
                                                                ssPlayer, ssOwner, bClicked, sOperation, event.getBlockFace());

            ssArgs.setMessagePart("!customer", ssPlayer.getName());
            ssArgs.setMessagePart("!owner", ssOwner.getName());
            ssArgs.setMessagePart("!player", ssPlayer.getName());
            ssArgs.setMessagePart("!world", ssPlayer.getPlayer().getWorld().getName());
            if(seller.getMisc() != null)
                ssArgs.miscSettings = seller.getMisc();
            Boolean bRequirementsOK = false;
            Boolean bRunOK = false;
            for(Map.Entry<SignShopOperation, List<String>> ssOperation : SignShopOperations.entrySet()) {
                ssArgs.set_operationParameters(ssOperation.getValue());
                bRequirementsOK = ssOperation.getKey().checkRequirements(ssArgs, true);
                if(!bRequirementsOK)
                    return;
            }
            if(!bRequirementsOK)
                return;
            ItemStack blacklisted = SignShopConfig.isAnyItemOnBlacklist(ssArgs.get_isItems(), ssArgs);
            if(blacklisted != null) {
                ssArgs.messageParts.put("!blacklisted_item", itemUtil.formatData(blacklisted.getData(), blacklisted.getDurability()));
                ssPlayer.sendMessage(SignShopConfig.getError("item_on_blacklist", ssArgs.messageParts));
                return;
            }
            if(event.getAction() == Action.LEFT_CLICK_BLOCK) {
                ssPlayer.sendMessage(SignShopConfig.getMessage("confirm", ssArgs.get_sOperation(), ssArgs.messageParts));

                ssArgs.special.deactivate();
                return;
            }
            ssArgs.special.deactivate();
            for(Map.Entry<SignShopOperation, List<String>> ssOperation : SignShopOperations.entrySet()) {
                ssArgs.set_operationParameters(ssOperation.getValue());
                bRunOK = ssOperation.getKey().runOperation(ssArgs);
                if(!bRunOK)
                    return;
            }


            if(event.getAction() == Action.RIGHT_CLICK_BLOCK) {
                // Seems to still be needed. TODO: Find a proper way to update the player inventory
                player.updateInventory();
            }

            List<String> chests = new LinkedList();
            for(Map.Entry<String, String> entry : ssArgs.messageParts.entrySet())
                if(entry.getKey().contains("chest"))
                    chests.add(entry.getValue());
            String[] sChests = new String[chests.size()]; chests.toArray(sChests);
            String items = (ssArgs.messageParts.get("!items") == null ? signshopUtil.implode(sChests, " and ") : ssArgs.messageParts.get("!items"));
            SignShop.logTransaction(player.getName(), seller.getOwner(), sOperation, items, economyUtil.formatMoney(ssArgs.get_fPrice()));
            ssPlayer.sendMessage(SignShopConfig.getMessage("transaction", ssArgs.get_sOperation(), ssArgs.messageParts));
            ssOwner.sendMessage(SignShopConfig.getMessage("transaction_owner", ssArgs.get_sOperation(), ssArgs.messageParts));
            return;
        }
        if(event.getItem() != null && seller != null && (event.getItem().getType() == Material.getMaterial("INK_SACK") || event.getItem().getType() == Material.getMaterial("REDSTONE"))) {
            if(!runSpecialOperations(event)) {
                signshopUtil.registerClickedMaterial(event);
            }
        }
        if(event.getAction() == Action.LEFT_CLICK_BLOCK && bClicked.getState() instanceof InventoryHolder) {
            itemUtil.updateStockStatusPerChest(bClicked, null);
        }
    }

}