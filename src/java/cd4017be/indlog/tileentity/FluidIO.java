package cd4017be.indlog.tileentity;

import java.io.IOException;

import com.mojang.authlib.GameProfile;

import cd4017be.api.protect.PermissionUtil;
import cd4017be.indlog.util.AdvancedTank;
import cd4017be.lib.BlockGuiHandler.ClientPacketReceiver;
import cd4017be.lib.Gui.DataContainer;
import cd4017be.lib.Gui.SlotTank;
import cd4017be.lib.Gui.TileContainer;
import cd4017be.lib.Gui.DataContainer.IGuiData;
import cd4017be.lib.Gui.TileContainer.TankSlot;
import cd4017be.lib.tileentity.BaseTileEntity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;

/**
 * 
 * @author cd4017be
 */
public abstract class FluidIO extends BaseTileEntity implements ITickable, IGuiData, ClientPacketReceiver {

	public static int CAP = 8000, MAX_SIZE = 127, SEARCH_MULT = 3;

	protected GameProfile lastUser = PermissionUtil.DEFAULT_PLAYER;
	public AdvancedTank tank = new AdvancedTank(CAP, true);
	/**bits[0-7]: x, bits[8-15]: y, bits[16-23]: z, bits[24-26]: ld0, bits[27-29]: ld1, bit[30]: can back */
	protected int[] blocks = new int[0];
	protected int dist = -1;
	protected boolean goUp;
	public boolean blockNotify;
	public int mode, debugI;

	@Override
	public void update() {
		int target = blocks[dist];
		byte dx = (byte)target, dy = (byte)(target >> 8), dz = (byte)(target >> 16);
		if (dist >= blocks.length - 1) {
			moveBack(dx, dy, dz);
			return;
		}
		int ld0 = target >> 24 & 7, ld1 = target >> 27 & 7;
		boolean canBack = (target & 0x40000000) != 0;
		EnumFacing dir = findNextDir(dx, dy, dz, ld0, ld1, canBack);
		if (dir != null) {
			int s = dir.ordinal();
			if (s < 2) {
				target = 0;
			} else if (s != ld0) {
				target = s << 24 | ld0 << 27;
			} else {
				target &= 0x7f000000;
				if (!canBack && ld1 >= 2) {
					EnumFacing ld = EnumFacing.VALUES[ld1];
					if (!isValidPos(dx - ld.getFrontOffsetX(), dy, dz - ld.getFrontOffsetZ()))
						target |= 0x40000000;
				}
			}
			blocks[++dist] = (dx + dir.getFrontOffsetX() & 0xff) | (dy + dir.getFrontOffsetY() & 0xff) << 8 | (dz + dir.getFrontOffsetZ() & 0xff) << 16 | target;
		} else moveBack(dx, dy, dz);
	}

	protected EnumFacing findNextDir(int x, int y, int z, int ld0, int ld1, boolean canBack) {
		if (isValidPos(x, y + (goUp ? 1 : -1), z))
			return goUp ? EnumFacing.UP : EnumFacing.DOWN;
		EnumFacing ld = EnumFacing.VALUES[ld0];
		if (ld0 >= 2 && isValidPos(x + ld.getFrontOffsetX(), y, z + ld.getFrontOffsetZ()))
			return ld;
		if (ld0 < 2 || ld1 < 2) {
			for (EnumFacing dir : EnumFacing.HORIZONTALS)
				if (isValidPos(x + dir.getFrontOffsetX(), y, z + dir.getFrontOffsetZ()))
					return dir;
			return null;
		}
		ld = EnumFacing.VALUES[ld1];
		if (isValidPos(x + ld.getFrontOffsetX(), y, z + ld.getFrontOffsetZ()))
			return ld;
		if (canBack && isValidPos(x - ld.getFrontOffsetX(), y, z - ld.getFrontOffsetZ()))
			ld.getOpposite();
		return null;
	}

	protected boolean isValidPos(int x, int y, int z) {
		int l = mode & 0xff;
		if (x > l || -x > l || y > l || -y > l || z > l || -z > l || !canUse(pos.add(x, y, z))) return false;
		int p = (x & 0xff) | (y & 0xff) << 8 | (z & 0xff) << 16;
		for (int i = dist - 1; i >= 0; i -= 2)
			if ((blocks[i] & 0xffffff) == p) return false;
		return true;
	}

	protected abstract boolean canUse(BlockPos pos);
	protected abstract void moveBack(int x, int y, int z);

	@Override
	public boolean hasCapability(Capability<?> cap, EnumFacing facing) {
		return cap == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T> T getCapability(Capability<T> cap, EnumFacing facing) {
		return cap == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY ? (T) tank : null;
	}

	@Override
	public void onPacketFromClient(PacketBuffer data, EntityPlayer sender) throws IOException {
		switch(data.readByte()) {
		case 0:
			blockNotify = !blockNotify;
			mode = (mode & 0xff) | (blockNotify ? 0x100 : 0);
			break;
		case 1:
			lastUser = sender.getGameProfile();
			byte l = data.readByte();
			if (l < 0) l = 0;
			else if (l > MAX_SIZE) l = (byte) MAX_SIZE;
			blocks = new int[l * SEARCH_MULT];
			dist = -1;
			mode = (mode & 0xf00) | (l & 0xff);
			break;
		case 2: if (tank.fluid != null) tank.decrement(tank.fluid.amount); break;
		case 3: tank.setLock(!tank.lock); break;
		}
	}

	@Override
	public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
		nbt.setTag("tank", tank.writeNBT(new NBTTagCompound()));
		nbt.setInteger("mode", mode);
		PermissionUtil.writeOwner(nbt, lastUser);
		return super.writeToNBT(nbt);
	}

	@Override
	public void readFromNBT(NBTTagCompound nbt) {
		super.readFromNBT(nbt);
		tank.readNBT(nbt.getCompoundTag("tank"));
		mode = nbt.getInteger("mode");
		blocks = new int[(mode & 0x7f) * SEARCH_MULT];
		blockNotify = (mode & 0x100) != 0;
		dist = -1;
		lastUser = PermissionUtil.readOwner(nbt);
	}

	@Override
	public void initContainer(DataContainer cont) {
		TileContainer container = (TileContainer)cont;
		container.addTankSlot(new TankSlot(tank, 0, 184, 16, (byte)0x23));
		container.addItemSlot(new SlotTank(tank, 0, 202, 34));
		container.addPlayerInventory(8, 16);
	}

	@Override
	public int[] getSyncVariables() {
		return new int[]{mode, dist >= 0 ? blocks[dist] : 0};
	}

	@Override
	public void setSyncVariable(int i, int v) {
		switch(i) {
		case 0: mode = v; break;
		case 1: debugI = v; break;
		}
	}

	@Override
	public boolean detectAndSendChanges(DataContainer container, PacketBuffer dos) {
		return false;
	}

	@Override
	public void updateClientChanges(DataContainer container, PacketBuffer dis) {
	}

}
