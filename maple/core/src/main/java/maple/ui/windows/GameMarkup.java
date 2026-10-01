package maple.ui.windows;

import maple.game.ItemInfo;
import maple.game.Names;
import maple.game.SkillInfo;
import maple.game.World;
import maple.gfx.Sprite;
import maple.net.model.Item;
import maple.ui.RichText;
import maple.ui.UiAssets;
import maple.wz.WzNode;

/** Resolves script-text tokens against the game data and the player's state. */
public final class GameMarkup implements RichText.Resolver {
    private final World world;
    private final UiAssets assets;

    public GameMarkup(World world, UiAssets assets) {
        this.world = world;
        this.assets = assets;
    }

    @Override
    public String text(char code, int id) {
        switch (code) {
            case 'h':
                return world.data() == null ? "" : world.data().stats.name;
            case 'p':
            case '@':
                return Names.npc(id);
            case 'o':
                return Names.mob(id);
            case 'm':
                return Names.map(id);
            case 't':
            case 'z':
                return ItemInfo.get(id).name;
            case 'q': {
                SkillInfo s = SkillInfo.get(id);
                return s == null ? "" : s.name;
            }
            case 'c': {
                int n = 0;
                if (world.data() != null) {
                    for (Item it : world.data().inventory(ItemInfo.inventoryType(id)).values()) if (it.itemId == id) n += it.quantity;
                }
                return Integer.toString(n);
            }
            case 'a': {
                // quest mob progress: questId * 10 + mob index (1-based), three digits per mob
                int quest = id / 10, index = id % 10 - 1;
                String progress = world.data() == null ? null : world.data().startedQuests.get(quest);
                if (progress == null || index < 0 || progress.length() < (index + 1) * 3) return "0";
                try {
                    return Integer.toString(Integer.parseInt(progress.substring(index * 3, index * 3 + 3)));
                } catch (NumberFormatException e) {
                    return "0";
                }
            }
            case 'u':
            case 'y':
                return Names.quest(id);
            default:
                return null;
        }
    }

    @Override
    public Sprite image(char code, String arg) {
        switch (code) {
            case 'i':
            case 'v':
                try {
                    return assets.sprite(ItemInfo.get(Integer.parseInt(arg)).iconRaw());
                } catch (NumberFormatException e) {
                    return null;
                }
            case 's':
                try {
                    SkillInfo s = SkillInfo.get(Integer.parseInt(arg));
                    return s == null ? null : assets.sprite(s.icon());
                } catch (NumberFormatException e) {
                    return null;
                }
            case 'W':
                return assets.sprite("UIWindow.img/Quest/" + arg);
            default: {
                // #f / #F: a WZ path like UI/UIWindow.img/QuestIcon/3/0
                String path = arg.replace('\\', '/');
                WzNode n = assets.wz.get(path.replace(".img/", ".img/"));
                if (n.exists() && !n.isCanvas() && n.get("0").exists()) n = n.get("0");
                return n.exists() ? assets.sprite(n) : null;
            }
        }
    }
}
