package game.data.container;

import config.Config;
import config.Version;
import game.data.registries.RegistryManager;
import se.llbit.nbt.ByteTag;
import se.llbit.nbt.CompoundTag;
import se.llbit.nbt.IntTag;
import se.llbit.nbt.SpecificTag;
import se.llbit.nbt.StringTag;

public class Slot {
    private int itemId;
    private int count;
    private SpecificTag nbt;

    public Slot(int itemId, byte count, SpecificTag nbt) {
        this.itemId = itemId;
        this.count = count;
        this.nbt = nbt;
    }

    public Slot(String itemName, byte count) {
        this(RegistryManager.getInstance().getItemRegistry().getItemId(itemName), count, null);
    }

    @Override
    public String toString() {
        return "Slot{" +
            "itemId=" + itemId +
            ", Name=" + RegistryManager.getInstance().getItemRegistry().getItemName(itemId) +
            ", count=" + count +
            ", nbt=" + nbt +
            '}';
    }

    public int getCount() {
        return count;
    }

    public int getItemId() {
        return itemId;
    }

    /** Registry name of the item (e.g. "minecraft:diamond"), or null if the id is unknown. */
    public String getItemName() {
        return RegistryManager.getInstance().getItemRegistry().getItemName(itemId);
    }

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.add("id", new StringTag(RegistryManager.getInstance().getItemRegistry().getItemName(itemId)));

        // 1.20.5 reworked the item NBT format: the stack size moved from "Count" (byte) to "count" (int)
        // and item data moved from "tag" into "components". Writing the old keys to a 1.20.5+ world makes
        // the client read a default count of 1 (so saved containers looked almost empty in-game). Match
        // the world's format; we don't reconstruct data components, so only id + count are written there.
        if (Config.versionReporter().isAtLeast(Version.V1_20_6)) {
            tag.add("count", new IntTag(count));
        } else {
            tag.add("Count", new ByteTag(count));
            if (nbt instanceof CompoundTag) {
                tag.add("tag", nbt);
            }
        }
        return tag;
    }

    public CompoundTag toNbt(int index) {
        CompoundTag tag = toNbt();
        tag.add("Slot", new ByteTag(index));
        return tag;
    }


}
