package share.mod;

import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * 团队验证的数据挂到 Litematica 自己的验证器上: 投影模组的世界高亮渲染器每帧向选中投影的验证器要
 * 「要高亮的位置」(颜色用玩家在 Litematica 里配置的验证器颜色)，这里返回的就是团队汇总的位置，
 * 所以显示效果和 Litematica 自带的验证器一模一样。
 */
final class TeamVerifier extends SchematicVerifier {
    private final VerifyModel model;

    TeamVerifier(VerifyModel model) {
        this.model = model;
    }

    @Override
    public List<MismatchRenderPos> getSelectedMismatchPositionsForRender() {
        return model.renderList();
    }

    @Override
    public List<BlockPos> getSelectedMismatchBlockPositionsForRender() {
        return model.renderPositions();
    }
}
