package game.data.entity.specific;

import game.data.container.Slot;
import game.data.entity.ObjectEntity;
import java.util.ArrayList;
import java.util.List;
import se.llbit.nbt.CompoundTag;
import se.llbit.nbt.ListTag;
import se.llbit.nbt.SpecificTag;
import se.llbit.nbt.Tag;

/**
 * A container minecart (chest / hopper minecart). Minecarts are entities, not block entities, so their
 * captured contents are written into the saved entity NBT (the chunk's "Entities" list) rather than as
 * a block entity. Populated by the (opt-in) auto-open sweep when it interacts with the minecart.
 */
public class ContainerMinecart extends ObjectEntity {
    private List<Slot> items;

    public ContainerMinecart() {
        super();
    }

    /** Set the captured contents (the container's slots) to be written when this entity is saved. */
    public void setItems(List<Slot> items) {
        this.items = items;
    }

    public List<Slot> getItems() {
        return items;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getZ() {
        return z;
    }

    @Override
    protected void addNbtData(CompoundTag root) {
        super.addNbtData(root);
        if (items == null) {
            return;
        }
        List<SpecificTag> slotTags = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Slot slot = items.get(i);
            if (slot != null) {
                slotTags.add(slot.toNbt(i));
            }
        }
        root.add("Items", new ListTag(Tag.TAG_COMPOUND, slotTags));
    }
}
