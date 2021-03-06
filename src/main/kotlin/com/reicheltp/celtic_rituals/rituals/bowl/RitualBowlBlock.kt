package com.reicheltp.celtic_rituals.rituals.bowl

import com.reicheltp.celtic_rituals.MOD_ID
import com.reicheltp.celtic_rituals.init.ModBlocks
import com.reicheltp.celtic_rituals.init.ModItems
import com.reicheltp.celtic_rituals.init.ModRecipes
import com.reicheltp.celtic_rituals.utils.addItemOrDrop
import java.util.Random
import net.minecraft.advancements.CriteriaTriggers
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.block.SoundType
import net.minecraft.block.material.Material
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.entity.player.ServerPlayerEntity
import net.minecraft.inventory.EquipmentSlotType
import net.minecraft.inventory.InventoryHelper
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.state.StateContainer
import net.minecraft.state.properties.BlockStateProperties
import net.minecraft.tileentity.TileEntity
import net.minecraft.util.BlockRenderLayer
import net.minecraft.util.DamageSource
import net.minecraft.util.Hand
import net.minecraft.util.ResourceLocation
import net.minecraft.util.SoundCategory
import net.minecraft.util.SoundEvents
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.BlockRayTraceResult
import net.minecraft.util.math.shapes.ISelectionContext
import net.minecraft.util.math.shapes.VoxelShape
import net.minecraft.world.Explosion
import net.minecraft.world.IBlockReader
import net.minecraft.world.IEnviromentBlockReader
import net.minecraft.world.World

/**
 * Central item required to perform rituals.
 *
 * The player puts all his items in a ritual bowl and burn it afterward.
 */
class RitualBowlBlock : Block(
    Properties.create(Material.WOOD)
        .variableOpacity()
        .hardnessAndResistance(1.0f)
        .lightValue(14)
        .sound(SoundType.WOOD)
) {
    companion object {
        private val SHAPE = makeCuboidShape(.0, .0, .0, 16.0, 8.0, 16.0)
        private val random = Random()
    }

    init {
        registryName = ResourceLocation(MOD_ID, "ritual_bowl")
        defaultState = stateContainer.baseState.with(BlockStateProperties.ENABLED, false)
    }

    override fun getShape(
      state: BlockState,
      worldIn: IBlockReader,
      pos: BlockPos,
      context: ISelectionContext
    ): VoxelShape {
        return SHAPE
    }

    override fun getRenderLayer(): BlockRenderLayer {
        return BlockRenderLayer.CUTOUT
    }

    override fun getLightValue(
      state: BlockState?,
      world: IEnviromentBlockReader?,
      pos: BlockPos?
    ): Int {
        return if (state!!.get(BlockStateProperties.ENABLED)) super.getLightValue(
            state,
            world,
            pos
        ) else 0
    }

    override fun hasTileEntity(state: BlockState?): Boolean = true

    override fun createTileEntity(state: BlockState?, world: IBlockReader?): TileEntity? =
        RitualBowlTile()

    override fun fillStateContainer(builder: StateContainer.Builder<Block, BlockState>) {
        builder.add(BlockStateProperties.ENABLED)
    }

    /**
     * Drops all stored items when block gets replaced.
     *
     * Borrowed from AbstractFurnaceBlock
     */
    override fun onReplaced(
      state: BlockState,
      worldIn: World,
      pos: BlockPos,
      newState: BlockState,
      isMoving: Boolean
    ) {
        if (state.block !== newState.block) {
            if (state.get(BlockStateProperties.ENABLED)) {
                // Destroying a running ritual will loose all items
                worldIn.setBlockState(pos, Blocks.FIRE.defaultState)
            } else {
                val tile = worldIn.getTileEntity(pos)
                if (tile is RitualBowlTile) {
                    InventoryHelper.dropInventoryItems(worldIn, pos, tile)
                }
            }

            super.onReplaced(state, worldIn, pos, newState, isMoving)
        }
    }

    override fun onBlockActivated(
      state: BlockState,
      worldIn: World,
      pos: BlockPos,
      player: PlayerEntity,
      handIn: Hand,
      hit: BlockRayTraceResult
    ): Boolean {
        if (handIn != Hand.MAIN_HAND) {
            return false
        }

        val tile = worldIn.getTileEntity(pos)
        if (tile !is RitualBowlTile) {
            return false
        }

        val inProgress = state.get(BlockStateProperties.ENABLED)

        // Extinguishes an ignited bowl, but removes all items.
        if (player.heldItemMainhand.item == Items.WATER_BUCKET) {
            worldIn.playSound(
                player,
                pos,
                SoundEvents.BLOCK_FIRE_EXTINGUISH,
                SoundCategory.BLOCKS,
                0.5f,
                2.6f + (random.nextFloat() - random.nextFloat()) * 0.8f
            )

            if (inProgress) {
                worldIn.setBlockState(pos, state.with(BlockStateProperties.ENABLED, false))
            }

            player.setItemStackToSlot(EquipmentSlotType.MAINHAND, ItemStack(Items.BUCKET))

            tile.clear()

            return true
        }

        // Ignites the bowl
        if (player.heldItemMainhand.item == Items.FLINT_AND_STEEL) {
            // TODO: This will ignite the bowl and start a ritual
            worldIn.playSound(
                player,
                pos,
                SoundEvents.ITEM_FLINTANDSTEEL_USE,
                SoundCategory.BLOCKS,
                1.0f,
                random.nextFloat() * 0.4f + 0.8f
            )

            if (player is ServerPlayerEntity) {
                CriteriaTriggers.PLACED_BLOCK.trigger(player, pos, player.heldItemMainhand)
                player.heldItemMainhand.damageItem(1, player, { it.sendBreakAnimation(handIn) })
            }

            if (!inProgress && !worldIn.isRemote) {
                val recipe = worldIn.server!!.recipeManager.getRecipe(
                    ModRecipes.BOWL_RITUAL_TYPE!!,
                    tile,
                    worldIn
                )

                worldIn.setBlockState(pos, state.with(BlockStateProperties.ENABLED, true))
                worldIn.pendingBlockTicks.scheduleTick(
                    pos,
                    ModBlocks.RITUAL_BOWL!!,
                    recipe.map { it.duration }.orElse(BowlRitualRecipe.DEFAULT_DURATION)
                )
            }

            return true
        }

        // We can add an item to be part of the ritual
        if (inProgress && !player.heldItemMainhand.isEmpty && tile.specialItem.isEmpty) {
            if (player.heldItemMainhand.item === ModItems.RITUAL_BAG) {
                // This special case is handles by ritual bag
                return false
            }

            tile.replaceSpecialItem(player.heldItemMainhand.split(1))

            return true
        }

        // Let player pick up the item from the ritual
        if (!inProgress && player.heldItemMainhand.isEmpty && !tile.specialItem.isEmpty) {
            player.setItemStackToSlot(EquipmentSlotType.MAINHAND, tile.specialItem)
            tile.replaceSpecialItem(ItemStack.EMPTY)

            player.playSound(SoundEvents.ENTITY_ITEM_FRAME_ADD_ITEM, 1.0f, 0.0f)

            return true
        }

        // Sneak pick pulls a stack from bowl
        if (!inProgress && player.heldItemMainhand.isEmpty && player.isSneaking) {
            for (i in 0 until tile.sizeInventory) {
                val stack = tile.getStackInSlot(i)
                if (stack.isEmpty) {
                    continue
                }

                player.addItemOrDrop(stack.split(stack.maxStackSize))
                return true
            }
        }

        if (!inProgress && !player.heldItemMainhand.isEmpty) {
            // Use item on bowl puts it in
            for (i in 0 until tile.sizeInventory) {
                val stack = tile.getStackInSlot(i)

                when {
                    stack.isEmpty -> {
                        val item = player.heldItemMainhand.split(1)
                        tile.setInventorySlotContents(i, item)

                        player.playSound(SoundEvents.ENTITY_ITEM_FRAME_ADD_ITEM, 1.0f, 1.0f)

                        return true
                    }
                }
            }
        }

        return false
    }

    override fun tick(state: BlockState, world: World, pos: BlockPos, random: Random) {
        if (!state.get(BlockStateProperties.ENABLED)) {
            return
        }

        world.setBlockState(pos, state.with(BlockStateProperties.ENABLED, false))

        if (!world.isRemote) {
            val tile = world.getTileEntity(pos) as RitualBowlTile
            val recipe =
                world.server!!.recipeManager.getRecipe(ModRecipes.BOWL_RITUAL_TYPE!!, tile, world)

            if (!recipe.isPresent) {
                // Fail
                world.createExplosion(
                    null,
                    DamageSource.MAGIC,
                    pos.x.toDouble(),
                    pos.y.toDouble(),
                    pos.z.toDouble(),
                    2.0f,
                    false,
                    Explosion.Mode.BREAK
                )
                return
            }

            recipe.get().getCraftingResult(tile)
        }
    }
}
