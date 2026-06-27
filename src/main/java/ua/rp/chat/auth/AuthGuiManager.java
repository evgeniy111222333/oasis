package ua.rp.chat.auth;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the in-game Chest GUI and Sign Editor prompts for authentication/registration.
 */
public class AuthGuiManager implements Listener {

    private final JavaPlugin plugin;
    private final AuthManager authManager;

    // Track active player sign locations (UUID -> Location)
    private final Map<UUID, Location> playerSignLocations = new ConcurrentHashMap<>();
    
    // Track player stages in auth (UUID -> Stage)
    private enum AuthStage {
        NONE,
        LOGIN_USER,
        LOGIN_PASS,
        REG_USER,
        REG_RPNAME,
        REG_EMAIL,
        REG_PASS,
        REG_CONFIRM,
        RECOVERY_EMAIL
    }
    private final Map<UUID, AuthStage> playerStages = new ConcurrentHashMap<>();

    // Temporary registration data cache
    private final Map<UUID, String> loginCache = new ConcurrentHashMap<>();
    private final Map<UUID, String> rpNameCache = new ConcurrentHashMap<>();
    private final Map<UUID, String> emailCache = new ConcurrentHashMap<>();
    private final Map<UUID, String> passwordCache = new ConcurrentHashMap<>();

    public AuthGuiManager(JavaPlugin plugin, AuthManager authManager) {
        this.plugin = plugin;
        this.authManager = authManager;
    }

    /**
     * Opens the main split-screen Chest GUI for authentication.
     */
    public void openWelcomeGui(Player player) {
        UUID uuid = player.getUniqueId();
        playerStages.put(uuid, AuthStage.NONE);

        Inventory gui = Bukkit.createInventory(null, 54, Component.text("§0§lOASIS RP — Авторизація"));

        // Fill GUI background
        ItemStack grayPane = createItem(Material.GRAY_STAINED_GLASS_PANE, "§7 ");
        ItemStack sandPane = createItem(Material.ORANGE_STAINED_GLASS_PANE, "§e ");
        ItemStack blackPane = createItem(Material.BLACK_STAINED_GLASS_PANE, "§0 ");

        for (int i = 0; i < 54; i++) {
            int col = i % 9;
            if (col < 4) {
                gui.setItem(i, grayPane); // Left side background
            } else if (col == 4) {
                gui.setItem(i, blackPane); // Divider line
            } else {
                gui.setItem(i, sandPane); // Right side background
            }
        }

        // --- Left Side: Action Buttons ---
        boolean isRegistered = authManager.getDatabase().isRegistered(uuid);

        if (isRegistered) {
            ItemStack loginButton = createItem(Material.GOLD_BLOCK, "§6§lВХІД В АКАУНТ",
                "§7",
                "§a▶ Натисніть для авторизації",
                "§7Вам потрібно буде ввести ваш логін та пароль."
            );
            gui.setItem(20, loginButton);
        } else {
            ItemStack registerButton = createItem(Material.EMERALD_BLOCK, "§a§lРЕЄСТРАЦІЯ",
                "§7",
                "§a▶ Натисніть для створення акаунта",
                "§7Вам потрібно буде вказати:",
                "§7- Логін",
                "§7- Рольове Ім'я (Іван Петренко)",
                "§7- E-mail",
                "§7- Пароль"
            );
            gui.setItem(20, registerButton);
        }

        ItemStack recoveryButton = createItem(Material.COMPASS, "§c§lВІДНОВЛЕННЯ ПАРОЛЯ",
            "§7",
            "§e▶ Натисніть, якщо забули пароль",
            "§7Допоможе відновити доступ через E-mail."
        );
        gui.setItem(38, recoveryButton);

        // --- Right Side: Character Preview ---
        ItemStack playerHead = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta skullMeta = (SkullMeta) playerHead.getItemMeta();
        if (skullMeta != null) {
            skullMeta.setOwningPlayer(player);
            skullMeta.displayName(Component.text("§6Ваш персонаж"));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("§7Minecraft Нікнейм: §f" + player.getName()));
            lore.add(Component.text("§7Статус: " + (isRegistered ? "§aЗареєстрований" : "§cНовий гравець")));
            skullMeta.lore(lore);
            playerHead.setItemMeta(skullMeta);
        }
        gui.setItem(24, playerHead);

        player.openInventory(gui);
    }

    /**
     * Opens the sign editor for typing text.
     */
    private void openSignInput(Player player, String line1, String line2) {
        Location loc = player.getLocation().clone();
        
        // Find a safe spot directly below the player (bedrock level to avoid visual collision)
        loc.setY(-60);
        Block block = loc.getBlock();
        block.setType(Material.OAK_SIGN);

        Sign sign = (Sign) block.getState();
        sign.setLine(0, "§6§lOASIS ROLEPLAY");
        sign.setLine(1, line1);
        sign.setLine(2, line2);
        sign.setLine(3, ""); // Text input line
        sign.update(true, false);

        playerSignLocations.put(player.getUniqueId(), loc);
        
        // Force client to open sign editor
        player.openSign(sign);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!authManager.isPendingAuth(player.getUniqueId())) return;

        // Check if it is our custom auth GUI
        String title = event.getView().getTitle();
        if (!title.equals("§0§lOASIS RP — Авторизація")) return;

        event.setCancelled(true); // Prevent item stealing

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        UUID uuid = player.getUniqueId();
        int slot = event.getRawSlot();

        if (slot == 20) {
            player.closeInventory();
            boolean isRegistered = authManager.getDatabase().isRegistered(uuid);
            if (isRegistered) {
                // Start Login Flow
                playerStages.put(uuid, AuthStage.LOGIN_USER);
                openSignInput(player, "§7Введіть логін:", "§8↓↓↓");
            } else {
                // Start Register Flow
                playerStages.put(uuid, AuthStage.REG_USER);
                openSignInput(player, "§7Створіть логін:", "§8(4-16 лат. літер)");
            }
        } else if (slot == 38) {
            // Start Recovery Flow
            player.closeInventory();
            playerStages.put(uuid, AuthStage.RECOVERY_EMAIL);
            openSignInput(player, "§7Введіть Email:", "§8↓↓↓");
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (!authManager.isPendingAuth(player.getUniqueId())) return;

        // Keep re-opening the menu if they close it while in auth
        if (event.getView().getTitle().equals("§0§lOASIS RP — Авторизація")) {
            AuthStage stage = playerStages.getOrDefault(player.getUniqueId(), AuthStage.NONE);
            if (stage == AuthStage.NONE) {
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        if (player.isOnline() && authManager.isPendingAuth(player.getUniqueId())) {
                            openWelcomeGui(player);
                        }
                    }
                }.runTaskLater(plugin, 3L);
            }
        }
    }

    @EventHandler
    public void onSignChange(SignChangeEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (!authManager.isPendingAuth(uuid)) return;

        Location signLoc = playerSignLocations.remove(uuid);
        if (signLoc != null) {
            event.setCancelled(true); // Cancel placing sign block update
            signLoc.getBlock().setType(Material.AIR); // Restore bedrock block to AIR

            String input = event.getLine(3);
            if (input == null) input = "";
            input = input.trim();

            final String text = input;
            AuthStage currentStage = playerStages.getOrDefault(uuid, AuthStage.NONE);

            // Process input on the next tick to allow Client to return to normal
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (!player.isOnline()) return;
                    handleStageInput(player, currentStage, text);
                }
            }.runTaskLater(plugin, 1L);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        playerStages.remove(uuid);
        loginCache.remove(uuid);
        rpNameCache.remove(uuid);
        emailCache.remove(uuid);
        passwordCache.remove(uuid);
        
        Location loc = playerSignLocations.remove(uuid);
        if (loc != null) {
            loc.getBlock().setType(Material.AIR);
        }
    }

    /**
     * State machine for processing in-game wizard inputs.
     */
    private void handleStageInput(Player player, AuthStage stage, String input) {
        UUID uuid = player.getUniqueId();

        if (input.isEmpty()) {
            player.sendMessage("§cПомилка: введення не може бути порожнім.");
            reopenStage(player, stage);
            return;
        }

        switch (stage) {
            case LOGIN_USER:
                loginCache.put(uuid, input);
                playerStages.put(uuid, AuthStage.LOGIN_PASS);
                openSignInput(player, "§7Введіть пароль:", "§8↓↓↓");
                break;

            case LOGIN_PASS:
                String login = loginCache.remove(uuid);
                player.sendMessage("§eАвторизація... Зачекайте.");
                if (authManager.webLogin(uuid, login, input)) {
                    player.sendMessage("§aАвторизація успішна! Приємної гри.");
                    cleanupPlayer(uuid);
                } else {
                    player.sendMessage("§cНевірний логін або пароль.");
                    openWelcomeGui(player);
                }
                break;

            case REG_USER:
                if (!input.matches("^[a-zA-Z0-9_]{4,16}$")) {
                    player.sendMessage("§cЛогін повинен бути від 4 до 16 латинських символів/цифр.");
                    reopenStage(player, stage);
                    return;
                }
                if (authManager.getDatabase().isLoginNameTaken(input)) {
                    player.sendMessage("§cЦей логін вже зайнятий іншим гравцем.");
                    openWelcomeGui(player);
                    return;
                }
                loginCache.put(uuid, input);
                playerStages.put(uuid, AuthStage.REG_RPNAME);
                openSignInput(player, "§7Введіть ПІБ персонажа:", "§8(Наприклад: Іван Петренко)");
                break;

            case REG_RPNAME:
                if (!input.matches("^[A-ZА-ЯІЄЇ][a-zа-яієї']+\\s+[A-ZА-ЯІЄЇ][a-zа-яієї']+$")) {
                    player.sendMessage("§cФормат має бути: Іван Петренко або Иван Петренко.");
                    reopenStage(player, stage);
                    return;
                }
                rpNameCache.put(uuid, input);
                playerStages.put(uuid, AuthStage.REG_EMAIL);
                openSignInput(player, "§7Введіть ваш Email:", "§8↓↓↓");
                break;

            case REG_EMAIL:
                if (!input.contains("@") || !input.contains(".")) {
                    player.sendMessage("§cВведіть коректну адресу пошти.");
                    reopenStage(player, stage);
                    return;
                }
                emailCache.put(uuid, input);
                playerStages.put(uuid, AuthStage.REG_PASS);
                openSignInput(player, "§7Створіть пароль:", "§8(мінімум 6 символів)");
                break;

            case REG_PASS:
                if (input.length() < 6) {
                    player.sendMessage("§cПароль занадто короткий. Мінімум 6 символів.");
                    reopenStage(player, stage);
                    return;
                }
                passwordCache.put(uuid, input);
                playerStages.put(uuid, AuthStage.REG_CONFIRM);
                openSignInput(player, "§7Повторіть пароль:", "§8↓↓↓");
                break;

            case REG_CONFIRM:
                String firstPass = passwordCache.remove(uuid);
                if (!input.equals(firstPass)) {
                    player.sendMessage("§cПаролі не збігаються.");
                    passwordCache.remove(uuid);
                    emailCache.remove(uuid);
                    rpNameCache.remove(uuid);
                    loginCache.remove(uuid);
                    openWelcomeGui(player);
                    return;
                }
                
                String finalLogin = loginCache.remove(uuid);
                String finalRpName = rpNameCache.remove(uuid);
                String finalEmail = emailCache.remove(uuid);
                
                player.sendMessage("§eСтворення акаунту... Зачекайте.");
                if (authManager.webRegister(uuid, finalLogin, finalRpName, finalEmail, firstPass)) {
                    player.sendMessage("§aРеєстрація успішна! Приємної гри.");
                    cleanupPlayer(uuid);
                } else {
                    player.sendMessage("§cПомилка створення акаунту.");
                    openWelcomeGui(player);
                }
                break;

            case RECOVERY_EMAIL:
                player.sendMessage("§aІнструкції з відновлення надіслано на пошту " + input + " (якщо вона вірна).");
                openWelcomeGui(player);
                break;

            default:
                openWelcomeGui(player);
                break;
        }
    }

    private void reopenStage(Player player, AuthStage stage) {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) return;
                switch (stage) {
                    case LOGIN_USER:
                        openSignInput(player, "§7Введіть логін:", "§8↓↓↓");
                        break;
                    case LOGIN_PASS:
                        openSignInput(player, "§7Введіть пароль:", "§8↓↓↓");
                        break;
                    case REG_USER:
                        openSignInput(player, "§7Створіть логін:", "§8(4-16 лат. літер)");
                        break;
                    case REG_RPNAME:
                        openSignInput(player, "§7Введіть ПІБ персонажа:", "§8(Наприклад: Іван Петренко)");
                        break;
                    case REG_EMAIL:
                        openSignInput(player, "§7Введіть ваш Email:", "§8↓↓↓");
                        break;
                    case REG_PASS:
                        openSignInput(player, "§7Створіть пароль:", "§8(мінімум 6 символів)");
                        break;
                    case REG_CONFIRM:
                        openSignInput(player, "§7Повторіть пароль:", "§8↓↓↓");
                        break;
                    case RECOVERY_EMAIL:
                        openSignInput(player, "§7Введіть Email:", "§8↓↓↓");
                        break;
                    default:
                        openWelcomeGui(player);
                        break;
                }
            }
        }.runTaskLater(plugin, 30L); // Delay 1.5 seconds so they read the error message
    }

    private void cleanupPlayer(UUID uuid) {
        playerStages.remove(uuid);
        loginCache.remove(uuid);
        rpNameCache.remove(uuid);
        emailCache.remove(uuid);
        passwordCache.remove(uuid);
    }

    // --- Helper UI items builder ---

    private ItemStack createItem(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(name));
            List<Component> components = new ArrayList<>();
            for (String line : lore) {
                components.add(Component.text(line));
            }
            meta.lore(components);
            item.setItemMeta(meta);
        }
        return item;
    }
}
