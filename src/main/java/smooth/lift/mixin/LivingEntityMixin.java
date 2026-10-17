package smooth.lift.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import smooth.lift.EscalatorSpeedManager;
import smooth.lift.EscalatorUtil;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家乘坐扶梯时的接管逻辑（把玩家贴 MTR 扶梯的**可见斜面**平滑送过去），以及【1.31】修掉的两个 bug。
 *
 * <h2>为什么必须接管：MTR 的碰撞面 ≠ 可见面</h2>
 * MTR 的 {@code escalator_step} 用**两段方盒**粗略近似 45° 的可见斜面
 * （4.0.5 实测：下坡半边顶面 +0.5、上坡半边顶面 +0.9375），而可见面的高度是
 * {@code y + t + 0.5}（t = 沿上坡方向的块内进度，0→1）。两者在 t=0 处相等，
 * 越往上差得越多 —— 到 t=1 时碰撞面比可见面**低 0.5 格**。
 * 所以「由原版物理站人」时，玩家的脚会明显低于可见斜面：他会**陷进台阶里**。
 * 本模组接管后自己算这个可见面高度（{@link #surfaceLineNear}），
 * 服务端再用 {@code noPhysics} 豁免「与方块碰撞」的位移校验（见 {@link #setPhysicsExempt}）。
 *
 * <h2>【1.31】修 ①：按 ctrl+W 疾跑会陷进扶梯里，松开按键又正常</h2>
 * 旧版只要看到移动输入（走 / 跑）就直接放手、把这一 tick 交还原版。于是玩家一按键，
 * 站立高度就由 MTR 的**碰撞面**决定（比可见面低最多 0.5 格）→ 陷进台阶；
 * 一松键本模组接回控制、把 Y 拉回可见面 → 「自己又出来了」。而且跟着扶梯链的相位
 * 走，陷进去的深度随时在变，所以是「有可能会陷」。
 *
 * <p>修法：**输入不再放手**，而是把玩家自己的位移（{@link #inputVector}，等价于原版的
 * 「按视角把输入旋到世界坐标」）叠加到扶梯的传送位移上，高度仍由本模组贴可见面算。
 * 于是走 / 跑 / 疾跑都全程贴着可见斜面，不再有高度跳变。
 * ★【1.38f】这条修法整个被用户推翻：他要「移动 / 奔跑时直接用 MTR 默认」（见类头【1.38f】），
 * 有输入时本模组已完全放手、这条叠加路径不再可达 —— 这里只留档历史。
 *
 * <h2>【1.31】修 ②：出了阶梯区还会在扶梯的铁板上被拖着走一段</h2>
 * 扶梯两头各有一块**静态**踏板（{@code orientation=landing_bottom|landing_top}，
 * 用的是 {@code mtr:block/escalator_step_landing}，没有 {@code #step} 面、纹丝不动，
 * 就是玩家说的「铁板」）。旧版把到站目标定在**整条链的最后一格**，也就是铁板的末端，
 * 于是玩家被一路拖过整块铁板。修法两条（配合 {@link #walkToEndpoint} 一起看）：
 * <ol>
 *   <li>到站目标改成**静态踏板前的那一格**（= 会动的阶梯 / 过渡块 / 平段传送带），
 *       于是「到站」这件事发生在铁板之前，不会再被拖过整块铁板；</li>
 *   <li>站在静态踏板上、且到站目标已经不在前方时 —— 判定为「乘客已经走出阶梯区」，
 *       **安静地**交还原版（不给末速度、不记冷却），于是铁板上彻底回到原版行为；
 *       而上扶梯的那一端（最低端踏板）目标仍在前方，照旧走「接管」路径，
 *       （★ 这一段在 1.35 被推翻：两端钢板都不该送人，见【1.35】。）</li>
 * </ol>
 * 判据用**沿运行轴的有符号距离**（方法里的 {@code axisDist}），不是欧氏距离 ——
 * 否则「站在铁板上、目标在身后」会和「骑到铁板前」混成同一件事。
 * （★ 这个判据在 1.35 被整条删掉，见【1.35】。）
 *
 * <p>1.32 进一步收紧：**只有铁板（静态踏板）上才放手**，斜坡 / 过渡块 / 平段一律送到
 * 铁板再交还原版（1.31 还允许在「到站点 + 0.45」放手，而那正是 1.32 抽搐的来源）。见下。
 *
 * <h2>【1.32】修 ③：到尽头会被「推一下」+ 扶梯上**有时候会抽搐**</h2>
 *
 * <b>① 「推一下」= 到站时给了一个末速度脉冲。</b>
 * 1.31 的到站分支会 {@code setDeltaMovement(沿轴方向 × speed)} 把玩家「吐」出去，
 * 而接管期间速度一直被清零、位置是直接摆上去的 ⇒ 速度从「恒定 0」突然变成
 * 「原版物理 + 一个额外冲量」，体感就是被推了一把。
 * <p>修法：**彻底不给末速度** —— 连 1.31 那套「接近到站点时线性减速」也一并去掉。
 * 接管期间速度恒为 0、位置直接摆，于是「最后一步接管」与「第一步原版物理」速度都是 0，
 * 交接天然连续。为什么不能改成「到站点正好减速归零」：那种曲线速度正比于剩余距离，
 * 玩家会指数逼近到站点而永远踏不上去（会卡在踏板前半格）。
 *
 * <b>② 「抽搐」= 交接点选在了可见面 ≠ 碰撞箱顶面的方块上。</b>
 * 坡段上**可见面比碰撞箱顶面高最多约半格**（这正是本类必须存在的理由，见上）。
 * 1.31 把交接点定在「到站点 + {@code ARRIVE_DISTANCE}」，而到站点是静态踏板**前的那一格**，
 * 在典型扶梯里就是过渡块 / 斜坡：玩家在那里被交还原版，脚立刻落到**碰撞箱**上 → 沉半格；
 * 旧代码还会先把他硬抬到 {@code targetPos.getY() + 1.0} → 先弹起来悬着；
 * 更糟的是放手后还记了 40 tick 冷却，玩家得以用「沉半格」的姿态在原版物理里待满 2 秒，
 * 冷却一过本模组重新接管、{@link #rideOnSurface} 又把脚拉回可见面 ⇒
 * 一沉一弹 = 抽搐（只有站在斜坡 / 过渡块上才明显，所以是「有时候」）。
 * <p>修法（一条不变量）：**交接点只放在「可见面 == 碰撞箱顶面」的方块上** ——
 * 也就是扶梯两头的**静态踏板**（{@code orientation = landing_*}，整立方，
 * 见 {@link #isStaticLanding}）。斜坡 / 过渡块 / 平段上一律**不放手**，继续贴着可见面全速送，
 * 玩家踏上踏板的瞬间脚已被 {@link #rideOnSurface} 贴在块顶（== 可见面）上，
 * 此时放手就是「原地站住」，零跳变。1.32 当时给踏板加了「到站点已不在前方」的容差
 * （{@code ARRIVE_DISTANCE + PLATE_SLACK}，多格平台），**1.35 把那个容差删掉了**：
 * 站在钢板上就放手，两端对称（钢板上不存在「上客」这回事，见【1.35】）。
 * 唯一例外是链条尽头**没有踏板**（玩家横着走出扶梯 / 被推离链）：那时
 * {@link #rideOnSurface} 找不到扶手方块、返回 false，由它放手，玩家正常落到真实地面上。
 * 同时**删掉放手冷却**：它没有任何正面作用，却正好制造了「在半格沉降的原版物理里待 2 秒」这个窗口。

 * <b>③ 顺带修掉两处会放大上面症状的隐患。</b>
 * <ul>
 *   <li>{@link #findEscalatorBelow} 原来是「从下往上取第一个扶梯方块」，坡段上脚底
 *       （{@code y + t + 0.5}）抬高后可能先撞到**下面那格**，从而读到相邻格块的
 *       {@code facing / direction} → 一个 tick 往反方向送人 → 肉眼就是抽一下。
 *       改成**取可见面离脚底最近的那一格**。</li>
 *   <li>静态状态表原来用裸 {@code UUID} 当键、普通 {@code HashMap}。**单人存档里
 *       客户端玩家与服务端玩家的 UUID 是同一个**，而两侧的 {@code travel} 都在跑本类、
 *       还是两个线程 —— 于是两侧互相覆盖状态、并发读写 HashMap。
 *       改成按「UUID + 哪一侧」分键 + {@link ConcurrentHashMap}（见 {@link #sideKey}）。</li>
 * </ul>
 *
 * <h2>【1.33】修 ④：走路 / 奔跑时要留住「阶梯感」+ 修掉「手脚不停摆动」</h2>
 *
 * 用户原话：「玩家在扶梯上移动或者奔跑的时候要保留阶梯的感觉，（mtr 扶梯被刷为停止状态时
 * 走在上面的感觉）因为现实世界在扶梯上行走也有走台阶的感觉」「另外修复玩家乘坐扶梯时
 * 手脚不停摆动的 bug」。两条其实是同一处病根的两面。
 *
 * <b>① 「手脚不停摆动」的真凶：{@code travel} 被 cancel ⇒ 走路动画的驱动函数从没被调用。</b>
 * 走路动画（手脚摆动）由 {@code LivingEntity#calculateEntityAnimation(boolean)} 驱动，
 * 它**位于 {@code travel} 的末尾**（1.20.4 实测；骑乘时则在 {@code travelRidden} 里）。
 * 本类在 travel 的 HEAD 就 {@code ci.cancel()} 了 ⇒ 该方法永不执行 ⇒
 * {@code WalkAnimationState} 冻在「踏上扶梯那一刻」的速度上；而渲染取的是
 * {@code position(f) = position - speed*(1-f)}，每帧随插值因子 f 变 ⇒ 手脚以
 * **每 tick 一个来回**的频率抖 —— 站在扶梯上一动不动也会抖，所以用户说的是「乘坐时」。
 * <p>修法：接管期间**自己驱动**（见 {@link #rideOnSurface} 之后的几行）：
 * {@code walkAnimation.update(min(4×自己走的位移长度, 1), 0.4f)} —— 位移只算**玩家自己的输入**
 * （{@link #inputVector}），扶梯的搬运不计入。于是站着被搬 ⇒ 动画衰减到 0、手脚回到自然站姿；
 * 自己在走 / 跑 ⇒ 手脚正常摆动（这正是「走」该有的样子）。
 * 顺带按原版 {@code Entity#move} 的系数补 {@code walkDist}（视角步幅摆动 bobView 读它），
 * 于是走着才有步幅晃动、站着被搬则完全没有晃动。
 *
 * <b>② 「阶梯感」：走路 / 奔跑时脚下改成按台阶走，而不是贴一条完全平滑的斜面。</b>
 * 本类为了不陷进台阶，一直把脚贴在**平滑**的 45° 可见面上 ⇒ 走 / 跑时像在玻璃上滑。
 * 而 MTR 扶梯被刷成停止状态时，脚下是**原版物理 + MTR 自己的碰撞箱**，
 * 那碰撞箱本来就是把斜面近似成**每半格一阶**的台阶（实测 {@code +0.5} / {@code +0.9375}）
 * —— 用户要的就是这个脚感。
 * <p>修法：玩家**自己有位移输入**时，把站立高度按台阶量化（{@link #stairHeight}）。
 * 量化取「离可见面最近的一阶」：上下各偏最多 1/4 格，既给出台阶的节奏，
 * 又不会像「只往下取整」那样把脚埋进可见面半格
 * （那正是 1.31 报过的「陷进扶梯里」）。FLAT / LANDING 的可见面本来就落在阶上 ⇒ 量化后不变，
 * 平台与平段完全不受影响。
 * <br>★【1.38】阶高已改成按本 tick 位移**自适应**（{@link #TREAD_PITCH} 只是上限），
 * 见下面「【1.38】修 ⑥」—— 写死 0.5 会在慢走时留下一大批「只横移、不抬脚」的滑行 tick。
 *
 * <p>判据是「玩家自己这一 tick 有没有位移」（{@code selfMove}），不是「有没有站在扶梯上」：
 * 纯被扶梯搬着走时不加台阶、也不驱动动画，站着就是稳稳当当。
 * ★【1.38f】上述「有输入时量化台阶」已被用户取消（他要 MTR 默认，见类头【1.38f】）：
 * 有输入时本模组直接放手、量化不再可达；只剩「站着被搬时不驱动动画」这条仍由接管路径负责。
 *
 * <h2>【1.35】修 ⑤：扶梯**下端**的钢板还会把玩家推着走（两端行为不对称）</h2>
 * 用户原话：「扶梯上端钢板位置不会移动玩家了，但是扶梯下端的钢板位置还是会移动玩家」。
 * 1.31/1.32 只让**到站那一端**的踏板放手，判据是「站在踏板上 **且** 到站点已经不在前方」；
 * 于是上客端（上行扶梯的最低端 / 下行扶梯的最高端）因为「到站点还在很前面」，
 * 仍然被本模组按扶梯速度一路送过去 —— 玩家站在一块**纹丝不动的钢板**上却被拖着滑。
 *
 * <p><b>依据：MTR 自己也不在钢板上送人</b>（4.0.5 字节码，不是猜的）：
 * <ul>
 *   <li>{@code BlockEscalatorStep.getCollisionShape2}：{@code FLAT} / {@code TRANSITION_BOTTOM} 的
 *       碰撞箱顶面是 **15/16**；而 {@code SLOPE} / {@code TRANSITION_TOP} / {@code LANDING_*} 走
 *       {@code intersect(createCuboidShape(1,0,1,15,16,15), BlockEscalatorBase.getCollisionShape2)}，
 *       后者的 LANDING 分支返回 **{@code VoxelShapes.fullCube()}** ⇒ 钢板顶面正好 **16/16（满高）**。</li>
 *   <li>{@code Entity.checkInsideBlocks} 只在
 *       {@code BlockPos.containing(minY + 1.0E-7) … containing(maxY - 1.0E-7)} 这个范围里逐格调
 *       {@code BlockState.entityInside} ⇒
 *       **站在 15/16 的方块上时，脚踩的那一格本身也在范围内**（身体还探进上一格）⇒ MTR 推人；
 *       **站在满高的钢板上时，脚踩的那一格被 {@code +1e-7} 顶出范围** ⇒ MTR 一次都不推。</li>
 * </ul>
 * 也就是说：MTR 只在**会动的**方块（平段传送带 / 过渡块 / 斜坡）上送人，钢板本来就是它的「不送人区」。
 * 所以正确判据不是「按到站方向决定放不放手」，而是**只要站在静态踏板上就放手**
 * （{@link #isStaticLanding}），两端完全对称。
 *
 * <p><b>行为变化（符合现实、也与 MTR 一致）</b>：站在钢板上不会再被扶梯带走，
 * 要自己往前迈一步、踩到第一块**会动**的方块（过渡块 / 平段传送带）才被接上 ——
 * 现实扶梯的踏板同样是静止的，人被带动是从踏上会动的梯级那一刻起。
 * 到站判定、去冷却、去末速度这些 1.32 的结论全部保留：本类**依旧只在钢板上放手**。
 *
 * <h3>【1.38c】已按用户要求**删除** {@code /futirun}（原第 11 个可调项）</h3>
 * 用户原话：「删掉futirun功能，换回添加futirun功能之前的扶梯跑步功能」。
 * ⇒ 去掉 {@code RUN_BOOST} 倍率与 {@code EscalatorSpeedManager.isRunEnabled} 判据，
 * 玩家自己在扶梯上的位移**不再被额外放大**（回到 1.34 及以前的手感，与自己走路 / 疾跑同速）；
 * 指令、数据层（{@code defaultRun}/{@code blockRun}）与同步小包 {@code RUN_SYNC} 一并删除。
 * ★ 下面 ⑦ 的**阶高自适应必须保留** —— 那修的是节拍，与 futirun 无关。
 *
 *
 * <h2>【1.36】修 ⑥：在**向下**的扶梯上行走 / 跑步会被抬升约 2 格（被抬到栏板上）</h2>
 * 用户原话：「为什么玩家在向下的扶梯上行走或跑步会被抬升一段距离？大约2格左右」。
 *
 * <p><b>病根：MTR 每一格台阶的「正上方」都摆了一块侧板方块，而本类的候选方块判据
 * （旧版私有 {@code isEscalator}：类名含 {@code escalator}）把侧板也算成了「能站的扶梯方块」。</b>
 *
 * <p>证据（MTR 4.0.5 字节码，不是猜的）：
 * <ul>
 *   <li>{@code ItemEscalator.useOnBlock2} 放一格扶梯会写 4 个方块：两列台阶（{@code side=left} 在 {@code pos}、
 *       {@code side=right} 在 {@code pos.offset(playerFacing.rotateYClockwise())}）**外加它们各自
 *       {@code pos.up()} 处的 {@code BlockEscalatorSide}**；而 {@code BlockEscalatorStep.getStateForNeighborUpdate2}
 *       一旦发现上方不是侧板就把自己变空气 ⇒ <b>每格台阶正上方必有一块侧板</b>。</li>
 *   <li>{@code BlockEscalatorSide extends BlockEscalatorBase} ⇒ 侧板同样带 {@code orientation} 属性，
 *       并且它自己也是一条斜链（MTR 的 {@code getOrientation} 只跟**同类型**方块比邻居）⇒
 *       侧板的 {@code orientation} 与正下方那块台阶一一对应。</li>
 *   <li>而 {@link #surfaceLineOfBlock} 只看 {@code orientation} + 方块坐标 ⇒ 对侧板算出来的「可见面」
 *       **正好比台阶高 1.0 格**（{@code (y+1) + t + HOVER} vs {@code y + t + HOVER}）。</li>
 * </ul>
 *
 * <p><b>为什么只在「向下」时犯</b>：下行 = 沿 {@code -facing} 走；每跨到下一格，那格的台阶比脚下低 1 格，
 * 而它的**侧板恰好就在自己脚底那个高度**（「下一格台阶的侧板」= 「脚下这一格的台阶」）⇒
 * 「取可见面离脚底最近的那一格」在跨格瞬间会在**台阶**与**侧板**之间形成 0.5 : 0.5 的平分，
 * 胜负由浮点噪声决定；一旦选中侧板，脚底就被抬到栏板高度，而且此后每一 tick 侧板都仍然是「最近的」
 * ⇒ **自我强化，一路骑在栏板上**。上行时下一格的台阶在头顶 1 格、侧板在头顶 2 格，
 * 脚下那格的台阶永远最近 ⇒ 不犯。
 *
 * <p><b>量化</b>（离线复现工具 {@code _tools/sim_down_escalator.py}，按 MTR 真实布局建模 ——
 * 台阶链 + 每格正上方侧板 —— 再把本类逐字搬成 Python）：
 * 修复前「下行 + 顺流行走」脚底最高**高出台阶可见面 1.20 格**（选中侧板的 tick 占 47%，疾跑时 88%），
 * 换算到真实可见面约 1.7 格 —— 正是用户说的「大约 2 格」；修复后最大只有 0.24 格
 * （= 1.33 故意留的 {@link #TREAD_PITCH} 半格量化的 1/4 误差）。
 * 上行 / 逆行 / 从平台起步的所有对照场景修复前后都干净，与用户说的「只有向下」完全吻合。
 *
 * <p><b>修法</b>：「脚下那格」的判据从「类名含 escalator」收紧为**「是阶梯方块」**
 * （{@link EscalatorUtil#isEscalatorStep}，即 MTR 的 {@code BlockEscalatorStep}）。
 * 语义上也更对：**只有台阶是承重面**，侧板是栏板 —— MTR 自己也不在侧板上送人
 * （侧板没有 {@code direction}/{@code status} 属性，也没有 {@code onEntityCollision2} 的推力分支）。
 * 顺带删掉本类里与 {@link EscalatorUtil} 重复的那份判据实现，免得「一处改、一处漏」。
 *
 * <p>★ 教训：这是**「候选集合被放宽」型**的 bug —— 判据从「台阶」放宽到「任何扶梯方块」时，
 * 视觉上「多出来的那一圈方块」（栏板 / 侧板 / 裙板）会悄悄混进几何计算，而它们与主体的面高差
 * 恰好是整数格，正好落在「取最近面」判定的平分线上。凡「按类名 / 名字含某关键词」做筛选的地方，
 * 都要多问一句：**这个关键词还会命中谁？**
 *
 * <h2>【1.37】修 ⑤：扶梯**扶手（护栏 / 侧板）的碰撞箱失效** —— 接管期间横向移动要自己过一遍护栏</h2>
 * 用户原话：「扶梯扶手部分的碰撞箱没了，修复这个bug」。
 *
 * <b>为什么本模组会把护栏的碰撞搞没</b>：接管期间玩家是**直接摆位置**的
 * （{@link #rideOnSurface} 里的 {@code setPos}），完全不经过原版的方块碰撞解析 ——
 * 这是必须的：贴可见面时玩家的脚本来就在 MTR 台阶碰撞箱的**里面**（可见面比碰撞面高最多半格，
 * 也正是 {@link #setPhysicsExempt} 要用 {@code noPhysics} 的原因），
 * 一旦让原版去解碰撞，玩家会被台阶自己的碰撞箱卡死。
 * 但「台阶的碰撞箱」和「护栏的碰撞箱」是两回事：MTR 的侧板（{@code BlockEscalatorSide}）
 * **有自己真实的碰撞箱**，而它恰好是玩家横向走出去时唯一该拦住他的东西。
 * 两者一起被绕过 ⇒ 玩家能一头钻进护栏、从扶梯侧面掉下去。
 *
 * <p><b>侧板确实有碰撞箱（MTR 4.0.5 字节码，不是猜的）</b>：
 * {@code BlockEscalatorSide.getCollisionShape2} = {@code VoxelShapes.combine(自己的轮廓,
 * super.getCollisionShape2(...), BooleanBiFunction.AND)}（求交）；而其轮廓是
 * {@code getVoxelShapeByDirection(side==RIGHT ? 12 : 0, 0, orientation==LANDING_TOP ? 8 : 0,
 * side==RIGHT ? 16 : 4, 16, orientation==LANDING_BOTTOM ? 8 : 16, facing)} ——
 * 一块 **4/16 厚、满高**（{@code y 0..16}）的斜板；基类碰撞箱在 {@code SLOPE}/{@code TRANSITION_TOP}
 * 是「下 8/16 满板 ∪ 上 7/16 半板」（45° 两段方盒近似），其余是整立方 ⇒ 求交后仍然非空，
 * **世界坐标下就是「台阶正上方那一格的外侧 1/4 格、从踏面起往上约 0.94 格」的一块挡板**。
 *
 * <p><b>修法</b>（只动横向那一份位移，见 {@link #rideOnSurface} 与 {@link #clipCrossByHandrail}）：
 * 把玩家的位移拆成**沿运行轴**与**横轴**两份。沿轴那一份是扶梯的搬运 / 玩家顺着走，必须完整通过；
 * 横向那一份先过一遍「护栏碰撞」：把玩家 AABB 附近所有<b>侧板</b>的真实碰撞箱收集起来
 * （{@code state.getCollisionShape(level, pos)}，因此**与 MTR 版本无关** —— MTR 3.x 没有侧板时
 * 这里自然什么都收不到，退化成空操作），逐步试探横向位移，只允许「不比现在陷得更深」的那一段。
 *
 * <p>三个刻意的设计点：
 * <ul>
 *   <li><b>只认侧板，不认台阶</b>：台阶碰撞箱低于可见面，正是被绕过的原因；把它算进来
 *       ⇒ 玩家在坡段上会被自己的台阶卡住（1.31 报过的「陷进扶梯里」的同款）。</li>
 *   <li><b>判据是「穿透体积不许变大」，不是「不许相交」</b>：接管期间玩家本来就可能微微嵌进护栏
 *       （MTR 自己也会把站在护栏边的玩家往外挤），若写成「相交就拦」，玩家一旦贴上去就再也
 *       横移不动（连往回走都动不了）。改成「只拦变深」⇒ 贴着护栏能滑、能走回来，只是钻不进去。</li>
 *   <li><b>客户端与服务端同一份代码</b>：{@code travel} 两侧都跑，横向夹取是纯几何、结果一致，
 *       于是两侧位置不会因此分叉（否则又会被服务端拉回）。</li>
 * </ul>
 *
 * <p>★ 教训：接管移动时，「我要贴的那个面」和「我不该穿过的面」常常来自**同一族方块**，
 * 必须分开对待 —— 绕过碰撞只能绕过**我踩着的那块**，其余该挡的还得挡。
 *
 * <p>★ 范围界定：本次只补**扶手**。台阶方块之外的一般方块（墙 / 玻璃 / 栅栏）此前同样被绕过，
 * 但那是另一件事（与扶手无关、也没人报过），**故意不在这里一起改** ——
 * 免得在狭窄的扶梯井里引入「莫名其妙被挡住」的新手感。要扩大范围时，
 * 只需把 {@link #handrailPlates} 的判据从 {@link EscalatorUtil#isEscalatorSide} 换成
 * 「不是阶梯方块」，其余逻辑（只拦变深、沿轴不受牵连）原样适用。
 *
 * <h2>【1.38】修 ⑦：在扶梯上奔跑「变成平滑的移动、偶尔才有阶梯感」</h2>
 *
 * 用户原话：「futirun 指令开启后在扶梯上奔跑是正常的阶梯感，但是关闭这个指令玩家在扶梯上
 * 奔跑会变成平滑的移动，偶尔出现阶梯感」（追问：几乎一直在滑）。
 *
 * <b>根因：阶高写死 0.5，而「这个 tick 凑不满半格」的那些 tick 就是纯水平滑行。</b>
 * 台阶量化本身没坏（客户端 / 服务端都在跑；判据「玩家自己有位移输入」{@link Vec3 selfMove}
 * 已在 1.37 构建产物的字节码里核对过）。坏在**节拍**——量化是「沿轴位移跨过半格」才触发的：
 * <ul>
 *   <li>疾跑：一 tick 走 0.3308 格 ⇒ 平均 0.66 阶/tick ⇒ <b>35%</b> 的 tick 脚底不抬、只横着平移。</li>
 *   <li>走路：一 tick 走 0.2660 格 ⇒ 平均 0.53 阶/tick ⇒ <b>48%</b> —— 这就是「几乎一直在滑」。</li>
 *   <li>（当轮另有一个 {@code /futirun on} 把自身位移 ×1.5 ⇒ 一 tick 0.4712 格 ≈ 正好一阶、
 *       只有 10% 凑不满 —— 那正是用户当时说的「正常的阶梯感」。该功能已删除，见类头【1.38c】。）</li>
 * </ul>
 * 逐 tick 实测见 {@code _tools/sim_stair_pitch.py}（按 MTR 真实几何复刻，统计
 * 「水平在动而 ΔY=0」的 tick 占比）。
 *
 * <p>修法：【1.38】当时把 {@link #TREAD_PITCH} 降级为**上限**、阶高改为按本 tick 位移
 * 自适应（0.5 → 0.25 → 0.125）—— **该修法已在【1.38e】整条推翻**（削小阶高 = 削没台阶，
 * 正是用户后来报的「软绵绵」），这里只留档历史，当前行为见【1.38e】。
 *
 * <p>★ 只按 2 的幂次减半不是随手定的：0.5 / 0.25 / 0.125 都整除一个方块，所以
 * FLAT / LANDING 的块顶（可见面 {@code y+1.0}）在任何一档下量化后**仍然精确等于块顶**
 * —— 平段与站台不会被减阶改出一格虚假的小台阶。
 * ★ 也**不能**改成「每 tick 都动、幅度跟着位移走」（阶高 = 位移）：那恰好等价于完全不做量化
 * （脚始终贴在平滑可见面上），会直接退回用户报的「在滑」。台阶要「看得见」，就必须让
 * 阶高是**离散档位**、且比一 tick 的位移大不了多少。
 *
 * <p>★ 教训：「把常量改成可调」时，要连同它的**触发频率**一起看。这里的常量是*空间*节距，
 * 而玩家感受到的是*时间*节拍；空间节距固定 + 速度可变 ⇒ 节拍随速度变 ⇒ 同一条扶梯上
 * 不同的行程会得到两种脚感。凡「按位移跨过阈值才触发」的效果，都要回头检查
 * 慢速档下这个阈值是不是迟迟跨不过去。
 *
 * <h2>【1.38e】修 ⑧：阶高**改回固定的 0.5** —— 1.38 的自适应方向反了，并且补上踏步声</h2>
 *
 * 用户原话：「现在扶梯速度调到 1 的时候玩家在上面移动或者跑步几乎完全没有阶梯感了，速度调到 5
 * 会好一些，但还是没有以前版本的那种硬的阶梯感，感觉软绵绵的」「其他版本比如 fabric 1.20.1 的
 * 扶梯移动和奔跑效果是有阶梯感的，实在不会修就照着其他版本写」。
 *
 * <b>根因：1.38 按「本 tick 位移」把阶高往下对半砍，砍掉的正是台阶本身。</b>
 * 台阶感取决于**每阶有多高**，不取决于「每 tick 跨了几阶」：阶高被砍到 0.25 / 0.125 之后，
 * 一级只有可见斜面的 1/4 ~ 1/8 高，跳变幅度小到与「贴着平滑斜面走」在视觉上分不出来 ——
 * 这就是用户说的「软绵绵」。扶梯越慢（速度 1）本 tick 位移越小 ⇒ 砍得越狠 ⇒
 * **速度 1 最差、速度 5 稍好**（那时位移已够大、阶高回到 0.5），与用户描述逐条吻合。
 *
 * <b>为什么 0.5 是「对的」阶高（不是调出来的手感数）</b>：MTR 的 {@code escalator_step}
 * 用两段方盒近似 45° 可见斜面 —— 下坡半边顶面 {@code +0.5}、上坡半边 {@code +0.9375}
 * ⇒ 玩家在**停止状态**的扶梯上走（= 用户说的「以前版本」、也是 1.20.1 的脚感），
 * 脚下那条台阶线就是每半格一阶、阶高 0.5。而 1.33 的居中量化（{@link #stairHeight}：
 * 块底 + {@code round(表面 / 0.5) × 0.5}）落在 {@code y+0.5 / y+1.0 / y+1.5}，
 * 与 MTR 碰撞箱的三个台阶面（{@code +0.5 / +0.9375 / +1.5}）逐点吻合。
 * ⇒ 「照着 1.20.1 写」的结论就是**固定 0.5**，不是自适应。
 *
 * <b>1.38 那轮为什么误判成「在滑」</b>：把「{@code a/p < 1} 的 tick 脚底不抬」当成了缺陷。
 * 可那些 tick 本来就是**踩在同一级台阶上**（脚底不动、水平前进 = 站在台上），跨阶那一 tick
 * 抬 0.5 —— 这正是上楼梯的样子。当时真正观测到的现象是**台阶少**（{@code a/p ≈ 0.6}
 * ⇒ 每 1.6 tick 一级），而用「把阶高改小」去消除它，等于把台阶一起消掉了。
 * ⇒ 阶高恢复为常量 {@link #TREAD_PITCH}；{@code MIN_TREAD_PITCH} / {@code TREAD_STEP_RATE} /
 * {@code stairPitchFor} 一并删除（见 1.38e 的落地记录）。
 *
 * <b>同时补上 1.20.4 一直缺的那一半：踏步声。</b>
 * 1.20.1 的「硬」不只是几何 —— 玩家被交还原版物理后，原版 {@code Entity#move} 会
 * {@code moveDist += 0.6 × 三维位移}，每累计 1.0（≈1.67 格三维距离）播一次
 * {@code playStepSound}（脚下方块音色、音量 ×0.15）。而本模组自 1.31 改成「有输入也不放手」
 * 之后，{@code travel} 被 cancel、又从不调用 {@code move()} ⇒
 * **接管期间一声踏步都没有**（1.20.1 的「硬」有一半在这里）。
 * ⇒ 见 {@link #playRideStepSound}：按原版同一条公式自己累计、自己播。
 *
 * <p>★ 教训（比这个 bug 值钱）：**「把某个量调小」之前，先分清它是*表现*还是*成因*。**
 * 1.38 看到「有几个 tick 脚底没抬」就去调阶高 —— 而阶高同时就是「台阶有多大」这个**表现本身**，
 * 于是消掉现象的同时把特征也消掉了。凡「为消除某个不想要的观感」而去调一个**同时定义该观感**
 * 的参数，先想清楚有没有第二条路（这里是补踏步声 / 补原版物理感）。
 * ⇒ 反过来，「照着一个已知正常的版本（1.20.1）写」的正确做法，是先把它**为什么正常**拆成
 * 可验证的几条（台阶面高度 = 碰撞箱几何 + 原版 step 音效），再逐条落地，而不是调参数试手感。
 *
 * <h2>【1.38f】修 ⑨：玩家移动 / 奔跑时**直接交还 MTR 默认** —— 不自己写任何移动
 *     <b>（★【1.38h】已被取代：用户实测「放手 = 向下跳 + 向上陷」，改回「接管贴平滑可见面」，见下）</b></h2>
 *
 * 用户原话：「玩家在扶梯上移动或者奔跑时使用 mtr 默认的扶梯停止时奔跑的扶梯效果，
 * 不要自己写奔跑或者移动的功能，直接用 mtr 默认的」。
 *
 * 上一步（【1.38e】）是在「接管」框架内尽量逼近 1.20.1（固定阶高 + 自己补踏步声）；
 * 用户看完仍然觉得不对 —— 他要的不是「逼近」，是**一模一样的 MTR 默认**：
 * 扶梯被刷成停止状态时，走在上面的效果 = 原版物理 + MTR 台阶碰撞箱
 * （0.5 一阶的硬台阶、原版 step-up、原版踏步声、原版走路动画），一连串动作
 * 全部由原版 {@code travel} → {@code move()} 驱动；扶梯运行时则再叠 MTR 自己的
 * {@code onEntityCollision2} 推人。模组一条移动代码都不用写。
 *
 * <p><b>修法</b>：把 1.31 从放手条件里删掉的 {@code movementInput.lengthSqr() > 0.01} **加回来**
 * （见方法开头）—— 玩家一有移动输入：清掉方向记忆与 noPhysics 豁免、return、**不 cancel travel**。
 * 之后本模组的接管逻辑（facing / 方向判定、钢板放手、{@code rideOnSurface} 贴面、护栏夹取、
 * 量化台阶、踏步声、动画驱动）对这 tick **完全不可达**；原版 travel 继续跑完 ——
 * 与 1.20.1 / 与扶梯停止时的行为一致。
 * 潜行、骑乘、离地、上升本来就放手，维持原样。
 * （★【1.38k】推翻了「潜行放手」这一条：潜行也改成接管，见类头【1.38k】；其余三条不变。）
 *
 * <p><b>代价（用户明确选择接受）</b>：
 * <ul>
 *   <li>放手期间脚底由 MTR **碰撞面**决定（比可见面低最多半格）—— 1.31 当年就是为
 *       「疾跑陷进扶梯」才引入「有输入也不放手」的；现在这是用户要的原版效果。</li>
 *   <li>放手期间被搬运的速度 = MTR 默认 speed（{@code /futispeed} 只对「无输入时模组接管
 *       搬运」生效）。站着被搬仍是模组接管（平滑、可调速）—— 本次只动「移动 / 奔跑」。</li>
 * </ul>
 *
 * <p>★ 由此，1.38e 的 {@link #stairHeight} 量化与 {@link #playRideStepSound}
 * （「有输入才触发」的两块）在有输入时不再可达：玩家自己走时的脚感由原版物理给出，
 * 比任何手动复刻都准。保留这两处代码只是不动它们（无输入路径不触发，删除反而扩大回归面）。
 *
 * <h2>【1.38g】移植 `smoothlift1.20.4old` 的「玩家扶梯移动」：到站移交 + 减速 + 放手冷却</h2>
 *
 * 用户原话：「直接把 old 文件夹里的 玩家扶梯移动 功能移植到这个新版的 mod 里。
 * 之前那两个 bug 不着急修！」（old = `C:/Users/user/Desktop/1/1.20.4/smoothlift1.20.4old`，
 * mod 版本 1.8.1204，本模组历史版本的旧代码。）
 *
 * 对照 old（502 行 LivingEntityMixin）与主线：**「有输入就放手」两边已经一致**
 * （1.38f 已恢复），真正缺的是**无输入被搬**时的 old 细节：
 * <ol>
 *   <li><b>到站目标</b>：无输入被搬时以端部平台（{@code targetPos}）为中心点，
 *       old 在中心距 &lt; {@link #ARRIVE_DISTANCE} 时移交原版物理；主线 1.35 改成了
 *       「站在钢板上就放手」—— 两者共存：钢板放手优先（MTR 语义，用户后来点名要的），
 *       到站移交兜底无钢板 / 末端过渡段。</li>
 *   <li><b>接近端部线性减速</b>（{@link #SLOWDOWN_DISTANCE} = 1.5 格起按剩余距离减速）；
 *       这是 old 手感的一部分（1.32 曾以「渐近卡住」删掉，用户要 old 就恢复）。</li>
 *   <li><b>移交瞬间的末速度 + 脚高补位</b>：old 会把脚补到平台块顶（只上抬、限
 *       {@link #ENTRY_RISE}）再给一个沿运行轴的水平末速度，让玩家自己滑进平台
 *       （1.32 曾以「尽头被推一下」删掉 —— 若 old 手感里用户觉得突兀，可单独再调）。</li>
 *   <li><b>放手冷却 40 tick</b>（{@link #RELEASE_COOLDOWN_TICKS}）：移交后 40 tick 不接管，
 *       让交接在纯原版物理里走稳（1.32 曾以「抽搐窗口」删掉 —— 当时的问题是它配合
 *       「斜坡上放手」才造成抽搐，现在放手点要么钢板、要么到站补位，条件不同）。</li>
 * </ol>
 * 保留未动的主线安全修复：{@code isStaticLanding} 钢板放手（1.35）、
 * {@code surfaceLineNear} 候选只收台阶（1.36，防向下骑栏板）、
 * {@code sideKey}/ConcurrentHashMap（1.32 并发）、动画驱动（1.33 防手脚抖）、
 * 护栏夹取（1.37 —— 无输入横向为零时自然不触发）。
 *
 * <h2>【1.38h】修「向下跳一跳 + 向上陷进扶梯」：有输入**不放手**了，脚底恒贴平滑可见面</h2>
 *
 * 用户原话：「（向下扶梯上移动/跑步）不应该一跳一跳的，应该像正常下楼梯一样」「向上扶梯上
 * 移动或奔跑有时会陷进扶梯里，松开移动按键恢复」→ 判定：这两个 bug 都是 1.38f「放手交原版」
 * 的必然产物，而 1.38f 又严格执行了用户上一轮的「直接用 MTR 默认」—— 用户实测后不满意，修。
 *
 * 为什么放手必然出这两个现象（MTR 字节码几何，不是猜）：
 * <ul>
 *   <li><b>向下跳</b>：放手后脚底由 MTR 台阶**碰撞箱**决定（每半格一阶）；玩家水平连续走，
 *       一跨过下一阶边缘就瞬间失去支撑 → 竖直自由落体 0.5 格（1~2 tick 内掉完）→ 每级「哐当」
 *       = 一跳一跳。原版下楼梯（真方块）也这样，只是真楼梯每级更低、且玩家是主动踏下。</li>
 *   <li><b>向上陷</b>：碰撞箱顶面比可见斜面低最多 0.5 格（t=1 处），放手期间脚一直埋进
 *       「台阶可见面」里；只有松键、模组重新接管才会被拉回可见面 → 「松开按键恢复」。</li>
 * </ul>
 * 因此 1.38f 的「有输入放手」整条在此**取代**：
 * <ol>
 *   <li>恢复「有输入也不放手」（1.31 的结构），把自己的位移叠到扶梯传送位移上
 *       （{@link #inputVector} + selfMove，1.31 那套，代码一直在只是 1.38f 期间不可达）；</li>
 *   <li>脚底恒贴**平滑可见面**（stairFeet=false / 量化关闭）：向上 = 脚在可见斜面上（不陷）、
 *       向下 = 每 tick 连续下降 ΔY ≈ 沿轴位移（无 0.5 跳变）→ 两 bug 同时消失；</li>
 *   <li>走 / 跑的手感由原版同款元素承担：踏步声（{@link #playRideStepSound}，按原版 moveDist
 *       公式，每 1/0.6 格三维位移一声）+ 走路动画 / walkDist 视角摆动（1.33 驱动）——
 *       「硬」转由节拍正确的脚步音与步幅摆动承担，而不是竖直跳变；</li>
 *   <li>old 的到站移交（1.38g）条件收紧为**纯被搬**（{@code !selfWalking}）才触发：
 *       玩家自己走时不强制减速 / 不给末速度，自由走到钢板上由 1.35 静默放手。</li>
 * </ol>
 * ★ 1.38e 的「量化台阶」需求（硬阶梯视觉）暂时让位给本轮「平滑下楼梯 + 不陷」——
 * 用户前后两轮需求冲突（1.38e 要台阶感、现在要平滑）；本版取最新指令。
 * ★ 替代 1.38f 的行为级结论：**要「正常走路感」，接管贴可见面 + 补原版移动副产物
 * （踏步声/动画）即可，不需要也**不该**放手交原版 —— 放手 = 碰撞箱台阶 = 陷 + 跳。**
 *
 * <h2>【1.38i】走 / 跑时（上下都）带台阶，但向下不能「一下跳很高」：恢复量化 + 柔化下降</h2>
 *
 * 用户原话：「玩家不按移动键，向上，向下平滑移动」「玩家在扶梯上按下移动键奔跑或者移动，
 * 向下、向上都要带台阶。但是向下的扶梯不能一下子跳很高，要像正常下扶梯一样」
 *
 * 1.38h 的「有输入恒贴平滑面」做到了不陷、向下平滑，但把台阶感也一起去掉了 —— 用户要回台阶：
 * <ul>
 *   <li><b>无输入（纯被搬）</b>：保持平滑可见面（上 / 下都顺滑，1.38h 行为，不改）；</li>
 *   <li><b>有输入（自己走 / 跑）</b>：恢复 {@link #stairHeight} 量化（固定 {@link #TREAD_PITCH}=0.5，
 *       与 MTR 台阶面逐点吻合）→ 向上逐级抬、向下逐级落；</li>
 *   <li><b>向下柔化</b>：量化目标的下降限幅收紧为 <b>每 tick 至多一级</b>
 *       （{@code maxDrop = TREAD_PITCH + 0.1}，而不是 1.38e 的 {@code max(0.6, stepLen*1.6)}）。
 *       扶梯快 + 疾跑时目标值会「跨两级」，但脚底只被允许每 tick 降一级，剩下的下一 tick 再降
 *       ⇒ 一级一级踩下去，没有「一步掉一大格」的悬空摔落 = 正常下楼梯的节奏。</li>
 * </ul>
 *
 * <h2>【1.38j】撤销 1.38g 的「到站移交」：回到 1.32 ① ② + 1.35 的结论</h2>
 *
 * 用户原话：「玩家在移动到扶梯尽头时会被扶梯轻轻的推一下（平滑的位移会卡顿一下，
 * 上下扶梯都有这个问题）？而不是平滑站稳」。
 *
 * <p><b>症状与 1.32 ① ② 完全同源，判定为 1.38g 把同一处 bug 复活了。</b>
 * 1.38g 把 old 的到站移交（{@code ARRIVE_DISTANCE} 到站判据 + {@code SLOWDOWN_DISTANCE} 线性减速
 * + 脚高补位 + 沿轴末速度 + 40 tick 冷却）原样请回来时，**漏看了 1.31 已经改掉的一个前提**：
 * old 的 {@code targetPos} 是**端部静态踏板本身**，而 1.31 起 {@link #walkToEndpoint} 改成返回
 * **踏板前的那一格**（会动的过渡块 / 斜坡），1.35 又在其上加了「站在钢板上就放手」。
 * 于是到站触发点从「踏板上」挪到了「**会动的过渡块**上」（距该块中心 0.45 格 ≈ 块内进度 t≈0.05~0.10），
 * 三件事一起发生（离线仿真 `_tools_port/sim_end_push.py`，链布局见其文件头）：
 * <ol>
 *   <li><b>一抬一落</b>：脚被硬抬到 {@code targetPos.getY() + 1.0}（那一格的**块顶**），
 *       可那一刻该格可见面只有 {@code +0.5}~{@code +0.6} ⇒ 单 tick 硬抬 <b>+0.401 格</b>，
 *       抬完还悬在空中；接着 40 tick 冷却里脚自由落体掉回碰撞箱顶面 ⇒ 上行实测
 *       「单 tick 竖直跳变 &gt; 0.2 格」的 tick = <b>1 个（+0.401）</b>。</li>
 *   <li><b>被推一下</b>：交接时凭空给一个沿运行轴的水平末速度（默认速度下 <b>+0.0133 格/tick</b>）。
 *       接管期速度恒为 0（{@link #rideOnSurface} 每 tick {@code setDeltaMovement(ZERO)}）、
 *       位置直接摆 —— 速度从「恒定 0」突变成「原版物理 + 冲量」，体感就是被轻轻推了一把
 *       （正是 1.32 ① 的原话）。</li>
 *   <li><b>下行直接掉半格</b>：下端过渡块的可见面（t≈0.95 处 1.475）比它自己的碰撞箱顶面
 *       （15/16 = 0.938）高 <b>0.538 格</b> ⇒ 放手瞬间脚落 0.538 格（单 tick 最大 −0.240）。</li>
 * </ol>
 *
 * <p><b>修法：整段删除</b>（{@code ARRIVE_DISTANCE} / {@code SLOWDOWN_DISTANCE} /
 * {@code RELEASE_COOLDOWN_TICKS} / {@code PLAYER_RELEASE_TIME} 与那段到站块一起去掉），
 * 回到 1.32/1.35 的唯一一条不变量：**接管一直贴可见面送，交接点只放在静态钢板上**
 * （{@link #isStaticLanding}）。钢板上脚已被 {@link #rideOnSurface} 贴到块顶
 * （== 可见面 == 碰撞箱顶面），且接管期速度恒为 0 ⇒ 放手即「原地站住」，零跳变、零冲量。
 * 仿真对照（同一条链、同一出发点，玩家无输入）：
 * <pre>
 *          最大单 tick 竖直跳变&gt;0.2 的 tick 数        到站释放时
 *   现状   上行 1 个（+0.401）／下行 1 个（−0.240）   脚悬空 0.40 格后落下／脚掉 0.538 格
 *   修复后 上行 0 个／下行 0 个                        Δy=0，脚 5.001 == 钢板顶 5.000
 * </pre>
 * 两端对称、上下扶梯一致 —— 因为这本来就是「板子上不送人」的同一条规则。
 *
 * ★ 1.38g 的注释声称「与 1.35 钢板放手共存，到站移交只对**无钢板**情况兜底」，
 * 但那段代码里**没有任何「前方没有钢板」的判据**（只有 {@code horizontalDist &lt; ARRIVE_DISTANCE}），
 * 所以在带钢板的正常扶梯上它**总会抢在钢板规则之前触发** —— 注释与实现不符，这正是当时没被发现的原因。
 * ★ 无钢板链条（玩家横着走出 / 被推离）仍由 {@link #rideOnSurface} 返回 false 那条路径正常放手，不受影响。
 *
 * <h2>【1.38k】潜行（shift）不再放手：不该「加速移动」，也不该「陷进电梯里」</h2>
 *
 * 用户原话：「为什么玩家在扶梯上按下shift会加速移动？玩家按下shift之后不应该加速移动，
 * 只是蹲下就行。而且按下shift玩家有可能陷进电梯里」
 *
 * <p>两个症状同一个根因：**潜行被写进了 {@code travel} 开头的「整体放手」条件**
 * （{@code self.isShiftKeyDown()}）⇒ 一按 shift 本模组就把这一 tick 完全交还原版，
 * 而原版那条路在扶梯上正好有两处已被验证的坏处：
 * <ol>
 *   <li><b>「加速移动」</b>：放手后搬运速度不再是本模组的恒定 {@code getSpeed() / 20}
 *       （默认 1.0 ⇒ <b>0.05 格/tick</b>），而是 <b>MTR 自己的推送</b>。
 *       MTR 4.0.5 {@code BlockEscalatorStep.onEntityCollision2} 字节码实测 =
 *       每 tick 对重叠的每个阶梯块 {@code entity.addVelocity(±0.1, 0, 0)}
 *       （仅 {@code status=true} 时推；轴向与正负由 {@code facing}/{@code direction} 决定，
 *       四个朝向的对应关系逐条写在字节码里）。它是**累加**的，被地面摩擦（×0.546/tick）平衡后
 *       稳态约 <b>0.12 格/tick ≈ 2.4 格/秒</b> ⇒ 一按 shift 立刻比接管时的 0.05 快约 <b>2.4 倍</b>。
 *       （类头【1.38f】「放手期间被搬运的速度 = MTR 默认 speed」一句早就点明了这件事，
 *       只是当时没意识到潜行也走这条路。）</li>
 *   <li><b>「陷进电梯里」</b>：放手后脚底由 MTR 的**碰撞箱**决定，它是把 45° 可见斜面近似成
 *       每半格一阶的楼梯：{@code BlockEscalatorBase.getCollisionShape2} 的 SLOPE / TRANSITION_TOP
 *       分支 = {@code union(cuboid(0,0,0,16,8,16), getVoxelShapeByDirection(0,8,0,16,15,8,facing))}
 *       （下 8/16 满板 ∪ 上 7/16 半板，字节码实测），比可见面低最多约半格。
 *       **更关键的是潜行会关掉原版自动上台阶**（原版 {@code Entity.isSteppingCarefully()} = 潜行），
 *       而坡段是一级一级的碰撞台阶 ⇒ 蹲着被 MTR 沿轴推、却跨不上那半格 ⇒ 顶在台阶立面上
 *       「陷进扶梯里」。这也解释了为什么**只有**按 shift 才明显：直立走路时自己能跨上去。</li>
 * </ol>
 *
 * <p><b>修法</b>：把 {@code self.isShiftKeyDown()} 从放手条件里删掉 —— 潜行也照常接管，
 * 于是「蹲下」只剩它该有的表现（姿态、碰撞箱变矮），搬运仍是本模组的平滑恒定速度、脚仍贴可见面。
 * 代价与 1.38h 一致：接管期间不走原版物理。**唯一必须自己补的是潜行的减速系数**：
 * 原版潜行速度 = 普通行走的 <b>30%</b>，而这个系数**不在 {@code getSpeed()} 里**
 * （它作用在 {@code getSpeed()} 之后），所以显式乘上 {@link #SNEAK_STEP_FACTOR}。
 * ★ 观感旋钮：想「蹲下但不减速」把该常量改成 {@code 1.0} 即可（单常数、一行）。
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

    /**
     * 【1.35】上面这里原有 1.32 的两个容差常量 —— {@code ARRIVE_DISTANCE = 0.45} 与
     * {@code PLATE_SLACK = 1.5}（「站在踏板上 **且** 到站点已不在前方」那条判据用的）。
     * 1.35 把判据简化成**「站在静态踏板上」本身**，于是沿运行轴的有符号距离（{@code axisDist}）
     * 与到站点（{@code targetPos}）一起删掉，两个常量也就没有用处了。见类头【1.35】。
     *
     * <p>顺便记下 1.31 的减速项（{@code SLOWDOWN_DISTANCE}）与放手冷却
     * （{@code RELEASE_COOLDOWN_TICKS}）为什么在 1.32 被删：减速会让玩家渐近停在到站点前、
     * 永远踏不上踏板；冷却则正好把玩家留在「半格沉降的原版物理」里 2 秒，是抽搐的窗口期。
     */
    /**
     * 【1.31】把玩家自己的移动速度（{@link LivingEntity#getSpeed()}）换成**每 tick 位移**的系数。
     *
     * <p>原版玩家铺装路面上的实测速度是 **4.317 格/秒**（走路）/ **5.612 格/秒**（疾跑），
     * 即每 tick 0.2159 / 0.2806 格；而 {@code MOVEMENT_SPEED} 属性分别是 0.1 / 0.13
     * （疾跑的 +30% 是原版挂在属性上的修饰符，所以 {@code getSpeed()} 自己就区分得开）。
     * 两者相除即 ≈ 2.16，对走路和疾跑都成立。
     *
     * <p>也就是说：**不要**直接把 0.1 当成「每 tick 走 0.1 格」用 —— 那是属性值，不是位移。
     */
    private static final double SPEED_TO_STEP = 2.16;
    private static final int MAX_WALK_LENGTH = 512;

    /**
     * 【1.38k】原版**潜行**速度系数 = 普通行走的 30%。
     *
     * <p>为什么必须自己乘：1.38k 之前潜行是「{@code travel} 开头直接放手」，交给原版算，
     * 所以那时不需要管；现在潜行也接管了，而**这个系数不在 {@link LivingEntity#getSpeed()} 里**
     * —— 原版是先取 {@code getSpeed()} 再算位移，潜行的 0.3 作用在后者上。见类头【1.38k】。
     *
     * <p><b>★ 观感旋钮</b>：把它改成 {@code 1.0} 就变成「蹲下但不减速」（只管姿态与碰撞箱高度）。
     */
    private static final double SNEAK_STEP_FACTOR = 0.3;

    /**
     * 坡段专用悬浮量：MTR 台阶碰撞箱比视觉斜面高约半格，坡段贴面时用半格余量
     * 避免脚部穿进碰撞箱。平台段（LANDING / FLAT / TRANSITION_BOTTOM）可见顶面
     * 就是块顶，站立面必须等于块顶，不能套用该余量。
     */
    private static final double HOVER = 0.50;
    /** 平直传送带/水平站台只需贴碰撞箱顶，仅加微小余量避免贴面抖动。 */
    private static final double FLAT_EPSILON = 0.001;
    /** 每 tick 允许的最小下降量，把下坡落差摊成平滑下降。 */
    private static final double MIN_DESCENT_PER_TICK = 0.15;
    /** 异常恢复时每 tick 允许的最大抬升量。 */
    private static final double ENTRY_RISE = 0.6;

    /**
     * 【1.38j】这里原有 1.38g 从 old 请回来的三条常量 —— {@code ARRIVE_DISTANCE = 0.45}、
     * {@code SLOWDOWN_DISTANCE = 1.5}、{@code RELEASE_COOLDOWN_TICKS = 40}。
     *
     * <p>用户实测报「到扶梯尽头会被轻轻推一下、平滑的位移会卡顿一下（上下扶梯都有）」，
     * 症状与 1.32 ① ② 完全同源（原因见类头【1.38j】）⇒ 三条**整条删除**，回到 1.32/1.35 的结论。
     * 1.38g 的原始说明留档于此：它们是从 `smoothlift1.20.4old` **原样移植**的
     * 到站判定距离 / 接近端部线性减速 / 放手后 40 tick 冷却；当时以为「与 1.35 钢板放手共存、
     * 只对无钢板情况兜底」，但那条件从未写进代码，实际会抢在钢板规则前触发。
     */

    /**
     * 【1.33】踏面节距：玩家**自己走动 / 奔跑**时，把站立高度按这个节距量化成台阶，
     * 走出「脚下是楼梯」的感觉（= 用户要的「MTR 扶梯被刷成停止状态时走在上面」的那种脚感）。
     *
     * <p>为什么是 0.5：MTR 的 {@code escalator_step} 碰撞箱本来就是把 45° 可见斜面近似成
     * **每半格一阶**的台阶（实测下坡半边顶面 {@code +0.5}、上坡半边 {@code +0.9375}），
     * 玩家在停止状态的扶梯上走，脚下就是这条台阶线。取 0.5 正好复现它——
     * 量化落点 {@code y+0.5 / y+1.0 / y+1.5} 与那条台阶线逐点吻合。
     *
     * <p>量化方向取「离可见面**最近**的一阶」（上下各偏最多 1/4 格）而不是「只往下取整」：
     * 只往下取整会让脚最深埋进可见面半格 —— 那正是 1.31 报过的「陷进扶梯里」；
     * 只往上取整则会明显悬空。取最近处偏差最小，且台阶节奏（每格抬升 0.5）完全一样。
     *
     * <p><b>【1.38e】它是一个**常量**，不是上限。</b>1.38 曾把它降级成「上限」、让阶高按本 tick
     * 位移自适应往下砍到 0.125 —— 那是**修错了方向**：阶高同时就是「台阶有多大」这个表现本身，
     * 砍小阶高等于把台阶一起消掉（用户报的「速度 1 时几乎完全没有阶梯感、软绵绵」，
     * 因为速度越慢砍得越狠）。那些「脚底不抬」的 tick 本来就是踩在同一级台阶上，不是缺陷。
     * 详见类头【1.38e】。
     */
    private static final double TREAD_PITCH = 0.5;

    /**
     * 【1.38e】踏步声的累计里程（格），键见 {@link #sideKey}。
     *
     * <p>复刻原版 {@code Entity#move} 的那两行：
     * <pre>
     *   this.moveDist += (float)(Math.sqrt(dx*dx + dy*dy + dz*dz) * 0.6F);   // 三维
     *   if (this.moveDist &gt; this.nextStep) { this.nextStep = (int)this.moveDist + 1; …踏步声… }
     * </pre>
     * 也就是「**每累计 1.0 就响一次**」，而 1.0 对应 {@code 1 / 0.6 ≈ 1.67} 格三维位移。
     * 这里只存「上次响过之后还剩多少」，语义与 {@code moveDist − nextStep} 等价，
     * 但不需要去碰原版那两个 private 字段（{@code moveDist} / {@code nextStep}）。
     *
     * <p>为什么必须有它：本模组自 1.31 起「有输入也不放手」，{@code travel} 被 cancel、
     * 又从不调用原版 {@code move()} ⇒ 接管期间原版一次踏步声都不会播。
     * 而 1.20.1（放手交还原版）是有的 —— 用户说的「以前版本那种硬的阶梯感」里，
     * 有一半就来自这串脚步声。
     *
     * <p>不清理：与 {@code moveDist} 一样是个纯里程计数器，留一点余量最多让第一步稍早 / 稍晚响。
     */
    private static final Map<String, Double> STEP_SOUND_DIST = new ConcurrentHashMap<>();

    /**
     * 【1.38e】踏步声的触发里程（格三维距离）= 原版的 {@code 1.0 / 0.6}。
     *
     * <p>水平走路时约每 1.67 / 0.266 ≈ 6 tick 响一次（≈3 Hz），
     * 45° 坡上三维位移是水平的 √2 倍 ⇒ 约每 4 tick 一次 —— 就是熟悉的脚步节奏。
     */
    private static final double STEP_SOUND_PER_DIST = 1.0 / 0.6;

    /**
     * 【1.38e】一 tick 最多补几声踏步（防止长时间没跑本逻辑后里程累积、一次性爆响）。
     * 正常每 tick 至多响 1 声，这里只是兜底。
     */
    private static final int STEP_SOUND_MAX_PER_TICK = 2;

    /**
     * 【1.37】横向夹取时逐步试探的步长（格）。
     *
     * <p>每 tick 横向位移本来就只有零点几格，取 0.05 最多试探 6~7 次，试探过程是**纯几何**
     * （碰撞箱只在开头收一次），所以开销可以忽略；好处是玩家会**贴住**护栏停下
     * （最多差 5 厘米），而不是停在离护栏一整个 tick 位移的地方（最多能差 0.3 格，肉眼可见）。
     */
    private static final double HANDRAIL_PROBE_STEP = 0.05;

    /**
     * 【1.37】判定「比现在陷得更深」的体积公差（格³）。
     *
     * <p>接管期间玩家与护栏的穿透体积本来就可能不是 0（贴着护栏站着时差不多 0，但浮点算出来的
     * 交集会有一点点噪声）。留一个 1e-7 的公差，避免「原地不动」都被判成变深 ——
     * 那样玩家会连往回走都走不动。
     */
    private static final double HANDRAIL_PEN_EPSILON = 1.0E-7;

    /**
     * 玩家「这一趟是往上还是往下」的记忆，键见 {@link #sideKey}。
     *
     * <p>【1.32】两个改动：① 键从裸 {@code UUID} 改成带侧别 —— **单人存档里客户端玩家与
     * 服务端玩家的 UUID 是同一个**，而 {@code travel} 在两侧都会跑本类（还是两个线程），
     * 裸 UUID 会让两侧互相覆盖状态；② 容器换成 {@link ConcurrentHashMap}，两侧并发读写不再踩踏。
     *
     * <p>1.31 的放手冷却表（{@code PLAYER_RELEASE_TIME}）已随冷却一起删除，见类头【1.32】②。
     */
    private static final Map<String, Boolean> PLAYER_DIRECTION = new ConcurrentHashMap<>();

    /**
     * 【1.38j】这里原有 1.38g 的放手冷却表 {@code PLAYER_RELEASE_TIME}（装「移交原版物理的时刻」，
     * 40 tick 内不再接管）。随到站移交整段删除一起去掉，见类头【1.38j】与【1.32】②。
     *
     * <p>顺带修掉 1.38g 移植时留下的一段**未闭合 javadoc**：它从「放手冷却表」讲到一半没有
     * {@code *&#47;}，把紧跟其后的 {@link #sideKey} 的说明整段吞进了注释里（本来只是难看，不影响编译）。
     */

    /** 状态表键：把「哪一侧」编进键里，见 {@link #PLAYER_DIRECTION}。 */
    private static String sideKey(LivingEntity entity) {
        return entity.getUUID() + (entity.level().isClientSide() ? ":c" : ":s");
    }

    /**
     * 只记录「由本模组亲自开启过 noPhysics 豁免」的玩家。
     * 关闭豁免时仅回滚这些玩家，绝不去写原版自己设置的 noPhysics —— 旁观模式的
     * 穿墙能力正是原版每 tick 写入的 noPhysics=true（Player#tick 中
     * noPhysics = isSpectator()），服务端 ServerPlayNetworkHandler 也以
     * !noPhysics 作为「移动校验 / 拉回」的开关。
     *
     * <p>豁免只在服务端写（{@link #setPhysicsExempt} 里判了 {@code isClientSide}），
     * 但键仍按侧分、容器仍用并发集合，跟 {@link #PLAYER_DIRECTION} 保持一致。
     */
    private static final Set<String> MOD_EXEMPT = ConcurrentHashMap.newKeySet();

    @Inject(method = "travel", at = @At("HEAD"), cancellable = true)
    private void smoothEscalator(Vec3 movementInput, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;

        if (!(self instanceof Player)) return;

        // 旁观模式直接放手：旁观者靠原版每 tick 的 noPhysics=true 穿墙，
        // 而本模组的接管逻辑会改写 noPhysics（含各条提前返回分支里的"关闭豁免"），
        // 一旦被写成 false，服务端的移动校验就会生效，旁观玩家穿墙时会被判定
        // "moved wrongly" 并拉回 —— 表现就是无法穿墙。旁观者也不参与乘坐，
        // 这里连状态一并清掉，且完全不碰 noPhysics。
        if (((Player) self).isSpectator()) {
            String spectatorKey = sideKey(self);
            PLAYER_DIRECTION.remove(spectatorKey);
            MOD_EXEMPT.remove(spectatorKey);
            return;
        }

        // ★【1.38h】有输入**不再放手**（取代 1.38f 的「放手交 MTR 原版物理」，见类头【1.38h】）：
        // 用户实测放手路线后的反馈 = 「向下的扶梯上一跳一跳、向上的扶梯上有时陷进去」——
        // 那正是放手交给原版物理的必然结果（向下：MTR 碰撞箱每半格一阶 → 跨格瞬间自由落体；
        // 向上：碰撞面比可见面低最多半格 → 脚埋进台阶）。
        // 修法：玩家有输入也进入接管，脚底恒贴**平滑可见面**（无量化）⇒
        //   向上 = 脚始终在可见斜面上（不落碰撞箱、不陷）；
        //   向下 = 每 tick 沿斜面连续下降（ΔY ≈ 沿轴位移，无 0.5 跳变，像正常下楼梯）。
        // 走 / 跑的手感（踏步声、走路动画、视角摆动）由本模组按原版公式补（见下方接管块），
        // 1.20.1 的「硬」转由「节拍正确的踏步声 + walkDist 视角摆动」承担。
        // 【1.38k】潜行（shift）**不再放手**。原来这里用 isShiftKeyDown() 把这一 tick 交给原版，
        // 但原版那条路在扶梯上有两处坏处（都是用户报过的）：
        //   ① 搬运速度变成 MTR 自己的推送 getVelocity(...).add(±0.1)/tick，稳态约 0.12 格/tick，
        //      比本模组恒定的 getSpeed()/20（默认 0.05）快约 2.4 倍 ⇒ 「按 shift 会加速移动」；
        //   ② 潜行会关掉原版自动上台阶，而 MTR 碰撞箱是每半格一阶的楼梯 ⇒ 坡段上跨不上去、
        //      顶在台阶立面上 ⇒ 「陷进电梯里」。
        // 现在潜行也照常接管，只是把玩家自己的输入按原版潜行系数放慢（见下面 selfStep）。
        // 骑乘 / 鞘翅 / 离地 / 上升仍然放手（原版才能防掉边缘、给足跳跃与飞行物理）。
        if (self.isPassenger() || self.isFallFlying()
                || !self.onGround()
                || self.getDeltaMovement().y > 0.01) {
            PLAYER_DIRECTION.remove(sideKey(self));
            setPhysicsExempt(self, false);
            return;
        }

        Level level = self.level();

        // 【1.32】这里在 1.38g 期间被塞回了一段「放手后 40 tick 内不再接管」的冷却，
        // 1.38j 又删掉了（见类头【1.38j】）。1.32 原本的理由仍然成立：它没有任何正面作用，
        // 却正好把玩家留在「半格沉降的原版物理」里待满 2 秒 —— 抽搐的观感有一半来自它。
        // 现在只在静态踏板上放手（判据就在下两行），踏板上不会沉格，冷却也就不需要了。

        BlockPos escalatorPos = findEscalatorBelow(level, self);
        if (escalatorPos == null) {
            PLAYER_DIRECTION.remove(sideKey(self));
            setPhysicsExempt(self, false);
            return;
        }

        // 【1.35】只要脚下这一格是**静态踏板**（玩家说的「钢板」）就立刻放手，不看方向、不看距离。
        // 判据放在最前面：钢板本身就是「非送人区」，MTR 在钢板上也不推人（见类头【1.35】），
        // 所以这里既不需要知道扶梯朝哪边跑，也不需要算到站距离。
        // 这样两端完全对称：末端钢板不送人（1.32 已修）、**进入端的钢板也不送人**（本次修复）。
        if (isStaticLanding(level, escalatorPos)) {
            PLAYER_DIRECTION.remove(sideKey(self));
            setPhysicsExempt(self, false);
            return;
        }

        BlockState state = level.getBlockState(escalatorPos);
        String key = sideKey(self);
        Direction facing = getFacingProperty(state);

        // 【1.38j】1.38g 在这里还声明了一个 {@code targetPos}（到站目标），随到站移交一起删掉。
        // 现在只需要「运行轴方向」{@code horizontal}，无 facing 时用两端连线 {@code fallbackDir}。
        Direction horizontal = null;
        Vec3 fallbackDir = null;

        if (facing != null) {
            Boolean running = escalatorProp(level, escalatorPos, "status");
            if (running != null && !running) {
                PLAYER_DIRECTION.remove(key);
                setPhysicsExempt(self, false);
                return;
            }

            BlockPos[] endpoints = findEndpointsAlongAxis(level, escalatorPos, facing);

            Boolean directionProp = escalatorProp(level, escalatorPos, "direction");
            boolean up;
            if (directionProp != null) {
                up = directionProp;
                PLAYER_DIRECTION.put(key, up);
            } else {
                Boolean cached = PLAYER_DIRECTION.get(key);
                if (cached != null) {
                    up = cached;
                } else {
                    up = distanceSq(self, endpoints[0]) <= distanceSq(self, endpoints[1]);
                    PLAYER_DIRECTION.put(key, up);
                }
            }

            horizontal = up ? facing : facing.getOpposite();
        } else {
            BlockPos[] endpoints = findEscalatorEndpoints(level, escalatorPos);
            if (endpoints == null || endpoints.length < 2) {
                setPhysicsExempt(self, false);
                return;
            }
            BlockPos lowestPos = endpoints[0];
            BlockPos highestPos = endpoints[1];

            boolean up;
            Boolean directionProp = getBooleanProperty(state, "direction");
            if (directionProp != null) {
                up = directionProp;
                PLAYER_DIRECTION.put(key, up);
            } else {
                Boolean cached = PLAYER_DIRECTION.get(key);
                if (cached != null) {
                    up = cached;
                } else {
                    up = distanceSq(self, lowestPos) <= distanceSq(self, highestPos);
                    PLAYER_DIRECTION.put(key, up);
                }
            }

            fallbackDir = up
                    ? new Vec3(
                    highestPos.getX() + 0.5 - (lowestPos.getX() + 0.5),
                    highestPos.getY() + 0.5 - (lowestPos.getY() + 0.5),
                    highestPos.getZ() + 0.5 - (lowestPos.getZ() + 0.5)
            ).normalize()
                    : new Vec3(
                    lowestPos.getX() + 0.5 - (highestPos.getX() + 0.5),
                    lowestPos.getY() + 0.5 - (highestPos.getY() + 0.5),
                    lowestPos.getZ() + 0.5 - (highestPos.getZ() + 0.5)
            ).normalize();
        }

        if (fallbackDir != null && fallbackDir.lengthSqr() < 1.0E-4) {
            setPhysicsExempt(self, false);
            return;
        }
        // 【1.31】没有 facing 属性（认不出来的扶梯）时保持旧行为：有移动输入就放手。
        // 那条路径是「朝两端连线的方向推」，叠上玩家自己的输入容易把他推歪，不值得为它改。
        if (fallbackDir != null && movementInput.lengthSqr() > 1.0E-7) {
            PLAYER_DIRECTION.remove(key);
            setPhysicsExempt(self, false);
            return;
        }

        // 【1.38h】玩家自己这一 tick 想走的位移（原版 getInputVector 的等价物）。
        // 有输入也接管（不再放手），把自己的位移叠加到扶梯传送位移上；
        // selfWalking 决定「踏步声 / 走路动画」是否驱动（扶梯的搬运不计入）。
        // 【1.38k】潜行也接管了 ⇒ 这里必须自己乘原版的潜行减速系数（它不在 getSpeed() 里）。
        double selfStep = self.getSpeed() * SPEED_TO_STEP;
        if (self.isShiftKeyDown()) {
            selfStep *= SNEAK_STEP_FACTOR;
        }
        Vec3 selfMove = inputVector(movementInput, selfStep, self.getYRot());
        boolean selfWalking = selfMove.lengthSqr() > 1.0E-7;

        double speed = EscalatorSpeedManager.getSpeed(level, escalatorPos) / 20.0;

        // 【1.38j】这里原有 1.38g 的「到站移交」整段（到站判据 / 线性减速 / 脚高补位 /
        // 沿轴末速度 / 40 tick 冷却）。它把交接点放在了**会动的过渡块**上（1.31 起
        // walkToEndpoint 返回的是踏板前那一格），于是「脚被硬抬 0.4 格再落回」「凭空多一个
        // 水平末速度」这两件事同时发生 = 用户报的「到尽头被推一下 + 平滑位移卡顿一下」。
        // 已整段删除，回到 1.32/1.35 的结论：**一直贴可见面送，只在静态钢板上静默放手**
        // （判据在本方法开头，那里脚已与钢板顶齐平、速度恒为 0 ⇒ 零跳变、零冲量）。
        // 详细成因、仿真数字与「为什么当时没被发现」见类头【1.38j】。

        // 接管移动：服务端关闭方块碰撞校验（noPhysics），
        // 使贴视觉面的位置（AABB 与台阶碰撞箱重叠）不会被 handleMovePlayer 拉回。
        setPhysicsExempt(self, horizontal != null);

        if (horizontal != null) {
            double stepX = horizontal.getStepX() * speed + selfMove.x;
            double stepZ = horizontal.getStepZ() * speed + selfMove.z;
            // 【1.38i】脚底贴面分两种：
            //   · 无输入（纯被搬）→ 平滑可见面（站着顺滑被送，上 / 下都不跳）；
            //   · 有输入（自己走 / 跑）→ 量化台阶（stairFeet=true，TREAD_PITCH=0.5）：
            //       向上逐级抬（台阶感）；向下也逐级落，但 maxDrop 限「每 tick 至多一级」——
            //       不会一步掉一大格（详见 rideOnSurface 的 maxDrop 注释），像正常下楼梯。
            double prevX = self.getX();
            double prevY = self.getY();
            double prevZ = self.getZ();
            if (!rideOnSurface(self, level, horizontal, stepX, stepZ, selfWalking)) {
                // 【1.31】目标位置附近已经没有扶梯了（玩家横向走出了扶梯 / 被推离了链）：
                // 交还原版物理。绝不能沿用「无重力水平直走」的兜底 —— 那会把玩家挂在半空。
                PLAYER_DIRECTION.remove(key);
                setPhysicsExempt(self, false);
                return;
            }
            // 【1.38e】踏步声：原版 Entity#move 每累计 1.0（三维位移 ×0.6）响一次，
            // 而本模组用 setPos 接管、从不调用 move() ⇒ 原版一声都不会响。
            // 1.20.1 的「硬阶梯感」有一半来自这串声音，这里按同一条公式补上。
            // 只算**玩家自己走**的那一份（站着被搬时脚不抬，不该有脚步声）。
            if (selfWalking) {
                playRideStepSound(self, self.getX() - prevX, self.getY() - prevY, self.getZ() - prevZ);
            }
        } else {
            self.move(MoverType.SELF, fallbackDir.scale(speed));
            self.setDeltaMovement(Vec3.ZERO);
            self.setOnGround(true);
            self.fallDistance = 0;
        }

        // 【1.33】自己驱动走路动画 + 视角摆动。
        //
        // 为什么必须自己驱动：走路动画（手脚摆动）与视角步幅摆动都靠
        // {@code LivingEntity#calculateEntityAnimation}（在 {@code travel} **末尾**调用），
        // 而本类在 travel 的 HEAD 就 {@code ci.cancel()} 了 ⇒ 那个方法从没被调用过 ⇒
        // {@code WalkAnimationState} 冻在「踏上扶梯那一刻」的速度上；而渲染时
        // {@code position(f) = position - speed*(1-f)} 每帧都随插值因子 f 变 ⇒
        // 手脚以每 tick 一个来回的频率抖（用户报的「手脚不停摆动」）。
        //
        // 驱动量只取**玩家自己的输入位移**（{@link #inputVector} 的结果，扶梯的搬运不计），
        // 于是：站着被搬 ⇒ 动画归零、手脚回到自然站姿；自己在走 / 跑 ⇒ 手脚正常摆动。
        // 位移长度和「×4 后夹到 1」「平滑系数 0.4」都照抄原版
        // {@code calculateEntityAnimation} → {@code updateWalkAnimation}。
        float selfLen = (float) Math.sqrt(selfMove.x * selfMove.x + selfMove.z * selfMove.z);
        self.walkAnimation.update(Math.min(4.0F * selfLen, 1.0F), 0.4F);
        // 视角步幅摆动（bobView）读的是 walkDist，原版在 Entity#move 里按实际位移 ×0.6 累加；
        // 本模组贴面移动不调用 move()，所以这里照同样的系数补上（同样只算玩家自己走的那份）。
        self.walkDist += selfLen * 0.6F;

        ci.cancel();
    }

    /**
     * 【1.31】把「以玩家为参照的输入向量」按视线偏航角旋到世界坐标，再乘上每 tick 位移，
     * 就是原版 {@code Entity.getInputVector} 在做的事（斜向走会被归一化，所以不会更快）。
     *
     * <p>{@code step} 传的是**每 tick 位移**（见 {@link #SPEED_TO_STEP}），不是速度属性值。
     * 潜行的 0.3 系数由**调用方**乘（见 {@link #SNEAK_STEP_FACTOR}）——【1.38k】起潜行也接管了，
     * 不再靠「上游放手交给原版」来回避这件事。
     */
    private static Vec3 inputVector(Vec3 input, double step, float yRot) {
        double len = input.lengthSqr();
        if (len < 1.0E-7) {
            return Vec3.ZERO;
        }
        Vec3 scaled = (len > 1.0 ? input.normalize() : input).scale(step);
        float sin = Mth.sin(yRot * 0.017453292F);
        float cos = Mth.cos(yRot * 0.017453292F);
        return new Vec3(scaled.x * cos - scaled.z * sin, scaled.y, scaled.z * cos + scaled.x * sin);
    }

    /**
     * 【1.38e】接管期间自己播原版的**踏步声** —— 原版 {@code Entity#move} 里那段被本模组绕过了。
     *
     * <p>原版公式（1.20.4 {@code Entity#move} 字节码逐条读出来的）：
     * <pre>
     *   this.moveDist += (float)(Math.sqrt(dx*dx + dy*dy + dz*dz) * 0.6F);   // **三维**位移
     *   if (this.moveDist &gt; this.nextStep) {                                // nextStep = (int)moveDist + 1
     *       this.nextStep = this.nextStep();
     *       … vibrationAndSoundEffectsFromBlock(…) → walkingStepSound(…) → playStepSound(…)
     *   }
     * </pre>
     * 也就是「**每累计 1.0 响一次**」，而 1.0 对应 {@code 1 / 0.6 ≈ 1.67} 格三维距离：
     * 水平走路约 6 tick 一声（≈3 Hz），45° 坡上三维位移是水平的 √2 倍 ⇒ 约 4 tick 一声
     * —— 正是用户要的那种「蹬、蹬、蹬」的上楼节奏。
     *
     * <p>为什么用**三维**位移而不是水平：原版就是三维，所以在扶梯这种既抬升又前进的地方，
     * 脚步自然比纯水平密。这是「照 1.20.1 写」的一部分（1.20.1 放手交还原版，
     * 这串声音是原版自己播的），本模组接管期间必须自己补。
     *
     * <p>音色取**脚下那格**（{@link LivingEntity#getOnPos()}，接管期间正好是扶梯台阶），
     * 音量 ×0.15 照抄 {@code LivingEntity#playStepSound}。走 {@code Entity#playSound} 这条
     * public 路径：客户端本地播、服务端只广播给其他人 ⇒ 不会听到两遍。
     */
    private static void playRideStepSound(LivingEntity self, double dx, double dy, double dz) {
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz) * 0.6;
        if (dist <= 0.0) {
            return;
        }
        String key = sideKey(self);
        double acc = STEP_SOUND_DIST.getOrDefault(key, 0.0) + dist;
        int plays = 0;
        while (acc > STEP_SOUND_PER_DIST && plays < STEP_SOUND_MAX_PER_TICK) {
            acc -= STEP_SOUND_PER_DIST;
            plays++;
        }
        if (plays >= STEP_SOUND_MAX_PER_TICK) {
            // 积压过多（例如长时间没跑本逻辑）：丢弃余量，免得接下来几 tick 连续补响。
            acc = 0.0;
        }
        STEP_SOUND_DIST.put(key, acc);
        if (plays == 0) {
            return;
        }
        BlockPos pos = self.getOnPos();
        BlockState state = self.level().getBlockState(pos);
        SoundType sound = state.getSoundType();
        self.playSound(sound.getStepSound(), sound.getVolume() * 0.15F, sound.getPitch());
    }

    /**
     * 服务端玩家豁免开关。玩家贴视觉斜面移动时 AABB 会与 MTR 台阶的
     * 碰撞箱重叠（碰撞箱比视觉面高约半格），服务端 handleMovePlayer 的
     * isPlayerCollidingWithAnythingNew 会把这种位置当作"moved wrongly"拉回。
     * noPhysics=true 让服务端跳过该校验并让 move() 无碰撞执行（位移与
     * 申报一致，bl3 偏差归零），从而既贴面又不被拉回。客户端无需豁免：
     * 接管期间不调用 move()，无碰撞副作用。
     *
     * <p>关闭豁免时只回滚本模组自己开过的玩家（MOD_EXEMPT 记账），
     * 不会把原版设置的 noPhysics 写成 false —— 旁观模式的穿墙依赖它。
     */
    private static void setPhysicsExempt(LivingEntity self, boolean exempt) {
        if (!self.level().isClientSide()) {
            String key = sideKey(self);
            if (exempt) {
                MOD_EXEMPT.add(key);
                self.noPhysics = true;
            } else if (MOD_EXEMPT.remove(key)) {
                self.noPhysics = false;
            }
        }
    }

    /**
     * 贴视觉表面移动。MTR 扶梯模型（escalator_step_slope + 移动纹理）等效表面高度：
     * - 平台段（FLAT / LANDING_TOP / LANDING_BOTTOM）：块顶 +1，玩家正好站在可见面上；
     * - 坡段（SLOPE）：块内上坡进度 t 的 45° 斜面，加 HOVER 让脚部避开高约半格的碰撞箱；
     * - 过渡块（TRANSITION_BOTTOM / TRANSITION_TOP）：用半格坡度把平台站立高度与坡段端点
     *   平滑连起来，于是整条链：底部平台(+1) → 过渡(+1~+1.5) → 坡段(+0.5 递增) →
     *   顶部过渡(+0.5~+1) → 顶部平台(+1)，处处连续，且两端都收在真实站立高度，
     *   玩家到达顶端不会被半格抬升后再落下。
     *
     * <p>【1.31】水平位移不再自带方向，改成上游直接给 {@code stepX/stepZ} —— 这样
     * 「扶梯的传送位移 + 玩家自己的走/跑位移」可以简单相加（见类头【1.31】①）。
     * 竖直方向的上限也按**合位移**算（原来的 {@code speed} 只是扶梯那一份），
     * 否则玩家在陡段上自己往上跑时会追不上可见面。
     *
     * <p>【1.33】{@code stairFeet} = 玩家这一 tick 在**自己走 / 跑**：站立高度改成按台阶量化
     * （见 {@link #stairHeight}），脚下就是 MTR 扶梯被刷成停止状态时的那种阶梯感；
     * 不走路（纯被搬）时贴平滑可见面，站着不抖。
     * 【1.38e】阶高恒为常量 {@link #TREAD_PITCH}（= 0.5，与 MTR 碰撞箱台阶面吻合）；
     * 1.38 曾「按本 tick 位移自适应取阶高」来消除「跨不满一阶的 tick」，但那会把台阶本身
     * 削小成平滑（见类头【1.38e】），已删除。
     * 【1.38i】有输入时上 / 下都走台阶；**向下**的下降限幅收紧为每 tick 至多一级
     * （{@code maxDrop = TREAD_PITCH + 0.1}），避免「一步跨两级掉一大格」—— 逐级下 = 正常下楼梯。
     *
     * @param stairFeet 是否按台阶（而非平滑可见面）放置脚底。
     * @param axis 扶梯的**水平运行轴**（上/下行的方向），用来把位移拆成「沿轴」与「横向」两份；
     *             为 null 或不是水平方向时不拆（只用于 {@code facing} 缺失的兜底路径）。
     * @return false = 目标位置附近已经找不到扶梯（玩家横向走出扶梯 / 被推离链），调用方应放手交还原版。
     *         true = 已经贴面移动完毕。
     */
    private static boolean rideOnSurface(LivingEntity self, Level level, Direction axis, double stepX, double stepZ,
                                         boolean stairFeet) {
        // 【1.37】把位移拆成「沿运行轴」与「横轴」两份：
        //   沿轴那一份 = 扶梯的搬运 + 玩家顺着走，必须完整通过（扶梯要送人）；
        //   横向那一份 = 玩家自己按 A/D 侧移，**必须先过扶手的碰撞**。
        // 为什么只在这时候管：接管期间用 setPos 直接摆位置，原版方块碰撞被整个绕过
        // （不绕不行，见类头【1.37】），于是护栏也跟着失效 —— 玩家能钻进护栏、
        // 从扶梯侧面掉下去。这里只把「横向」额外夹一道，纵向贴面逻辑一个字都不动。
        //
        // 【1.38e】`along` = 本 tick 沿运行轴的位移（45° 坡段上它也等于脚底该抬的高度）。
        // 1.38 曾拿它去挑「自适应阶高」（已删除，见类头【1.38e】）；现在只服务护栏裁剪。
        boolean horizontalAxis = axis != null && axis.getStepY() == 0;
        double along = 0.0;
        if (horizontalAxis) {
            double ax = axis.getStepX();
            double az = axis.getStepZ();
            along = stepX * ax + stepZ * az;
            double crossX = stepX - along * ax;
            double crossZ = stepZ - along * az;
            if (crossX != 0.0 || crossZ != 0.0) {
                double[] allowed = clipCrossByHandrail(level, self, crossX, crossZ);
                stepX = along * ax + allowed[0];
                stepZ = along * az + allowed[1];
            }
        }

        double newX = self.getX() + stepX;
        double newZ = self.getZ() + stepZ;
        double stepLen = Math.sqrt(stepX * stepX + stepZ * stepZ);

        // 【1.38e】走台阶时用**固定**阶高 TREAD_PITCH（= 0.5，与 MTR 碰撞箱的三个台阶面吻合）；
        // 1.38 那套「跨不满一阶就把阶高对半砍」的自适应已删除，见类头【1.38e】。
        // 0 = 不做量化（纯被搬着走，贴平滑可见面）。
        double treadPitch = stairFeet ? TREAD_PITCH : 0.0;

        double targetY = surfaceLineNear(level, self.getY(), newX, newZ, treadPitch);

        self.setDeltaMovement(Vec3.ZERO);
        self.setOnGround(true);
        self.fallDistance = 0;

        if (Double.isNaN(targetY)) {
            return false;
        }

        // 【1.38i】走台阶模式的下降限幅 = **每 tick 至多下一级**（0.6 ≈ TREAD_PITCH + 0.1 余量）。
        // 1.38e 那时是 max(0.6, stepLen*1.6) —— 速度快（扶梯 + 疾跑，a > 0.5）时会一次跨两级
        // （单 tick 掉 1.0 格）= 用户说的「向下一跳跳很高」。现改为固定上限：
        //   目标值跨两级时，本 tick 只允许降一级，剩下的下一 tick 再降 —— 逐级下，
        //   节奏与每级 0.5 的台阶一致，观感 = 正常下楼梯（一级一级踩下去，不悬空摔落）。
        // 非台阶模式（纯被搬）仍跟平滑面：maxDrop 只取 max(MIN_DESCENT, stepLen*1.6)。
        double maxDrop = stairFeet
                ? TREAD_PITCH + 0.1
                : Math.max(MIN_DESCENT_PER_TICK, stepLen * 1.6);
        // 【1.38i】抬升**不**限级（用户只对向下提出「不能一下跳很高」）：向上保持 1.38e 的
        // ENTRY_RISE + stepLen —— 速度 5 + 疾跑时本 tick 抬升目标会跨两级（1.0 ≈ a/p=1.16 级），
        // 抬升跟得上即是「快走楼梯」，强行限级反而让脚追不上目标、持续滞后下陷。
        double maxRise = ENTRY_RISE + stepLen;
        double newY = Math.max(Math.min(targetY, self.getY() + maxRise), self.getY() - maxDrop);

        self.setPos(newX, newY, newZ);
        return true;
    }

    /**
     * 【1.37】把**横向**（垂直运行轴）那一份位移夹到扶梯护栏外面，返回允许的 {@code {dx, dz}}。
     *
     * <p>做法：先把玩家 AABB 附近所有**侧板**（护栏）的真实碰撞箱收出来（{@link #handrailPlates}），
     * 再沿着横向位移逐步试探（步长 {@link #HANDRAIL_PROBE_STEP}），
     * **只允许「与护栏的穿透体积不比现在更大」的那一段**，第一个「钻得更深」的试探点就停。
     *
     * <p>为什么判据是「穿透体积不许变大」而不是「不许相交」：接管期间玩家本来就可能微微嵌进护栏
     * （MTR 自己也会把站在护栏边的玩家往外挤一点），而且一旦嵌进去，写成「相交就拦」的话
     * 连往回走都被拦住（原地不动也是相交）⇒ 玩家会**永久卡死在护栏里**。
     * 用穿透体积单调性做判据，就同时得到三个正确行为：贴着护栏能顺着滑、能走回中间、
     * 只是**不能再往护栏里钻**。
     *
     * <p>护栏碰撞箱来自 {@code state.getCollisionShape(level, pos)} —— 直接问 MTR 要，
     * 所以 MTR 换版本、或者装的是没有侧板的 MTR 3.x（那时这里收到空表、整个方法退化成空操作）
     * 都不用改一行代码。
     */
    private static double[] clipCrossByHandrail(Level level, LivingEntity self, double crossX, double crossZ) {
        AABB box = self.getBoundingBox();
        // 当前 AABB 与「目的 AABB」的并集：护栏盒子只需要收一遍，之后的试探是纯几何比较。
        List<AABB> plates = handrailPlates(level, box.minmax(box.move(crossX, 0.0, crossZ)));
        if (plates.isEmpty()) {
            return new double[]{crossX, crossZ};
        }

        double basePenetration = handrailPenetration(plates, box);
        double length = Math.sqrt(crossX * crossX + crossZ * crossZ);
        int probes = Math.min(16, Math.max(1, (int) Math.ceil(length / HANDRAIL_PROBE_STEP)));
        double allowed = 0.0;
        for (int i = 1; i <= probes; i++) {
            double fraction = (double) i / probes;
            double penetration = handrailPenetration(plates, box.move(crossX * fraction, 0.0, crossZ * fraction));
            if (penetration > basePenetration + HANDRAIL_PEN_EPSILON) {
                break;
            }
            allowed = fraction;
        }
        return new double[]{crossX * allowed, crossZ * allowed};
    }

    /**
     * 【1.37】收集给定 AABB 覆盖到的所有**侧板**（护栏）的碰撞箱，换算到世界坐标。
     *
     * <p>只认侧板、**刻意不认台阶**：台阶的碰撞箱比可见面低（坡段上最多低半格），
     * 正是本类必须绕过原版碰撞的原因（见类头【1.37】）；把台阶算进来，
     * 玩家在坡段上一横移就会被自己的台阶卡住。
     *
     * <p>范围就是一个 0.6×1.8×0.6 的 AABB，最多 2×3×2 个方块，每 tick 只跑一次。
     */
    private static List<AABB> handrailPlates(Level level, AABB box) {
        List<AABB> plates = new ArrayList<>();
        int x0 = Mth.floor(box.minX);
        int x1 = Mth.floor(box.maxX);
        int y0 = Mth.floor(box.minY);
        int y1 = Mth.floor(box.maxY);
        int z0 = Mth.floor(box.minZ);
        int z1 = Mth.floor(box.maxZ);
        for (BlockPos pos : BlockPos.betweenClosed(x0, y0, z0, x1, y1, z1)) {
            BlockState state = level.getBlockState(pos);
            if (!EscalatorUtil.isEscalatorSide(state)) {
                continue;
            }
            VoxelShape shape = state.getCollisionShape(level, pos);
            if (shape.isEmpty()) {
                continue;
            }
            for (AABB part : shape.toAabbs()) {
                plates.add(part.move(pos.getX(), pos.getY(), pos.getZ()));
            }
        }
        return plates;
    }

    /** 【1.37】玩家 AABB 与这些护栏碰撞箱的**穿透体积**之和（格³）；0 = 完全没接触。 */
    private static double handrailPenetration(List<AABB> plates, AABB box) {
        double volume = 0.0;
        for (AABB plate : plates) {
            double overlapX = Math.min(plate.maxX, box.maxX) - Math.max(plate.minX, box.minX);
            double overlapY = Math.min(plate.maxY, box.maxY) - Math.max(plate.minY, box.minY);
            double overlapZ = Math.min(plate.maxZ, box.maxZ) - Math.max(plate.minZ, box.minZ);
            if (overlapX > 0.0 && overlapY > 0.0 && overlapZ > 0.0) {
                volume += overlapX * overlapY * overlapZ;
            }
        }
        return volume;
    }

    /**
     * 在 (x,z) 竖直 ±3 格内找视觉表面线最接近 refY 的**阶梯方块**；找不到返回 NaN。
     *
     * <p>【1.33】{@code treadPitch} &gt; 0 = 玩家这一 tick 在**自己走 / 跑**：把选中那块的可见面
     * 按该阶高量化成台阶（见 {@link #stairHeight}），脚下就是 MTR 停止状态的那种阶梯感。
     * 挑块仍然用**未量化**的可见面比（否则同一格内的台阶会让挑块在两个 y 之间抖）。
     * 【1.38e】阶高恒为 {@link #TREAD_PITCH}（1.38 那套「按位移往下砍」的自适应已删除）；
     * 传 0 = 不做量化（纯被搬着走，贴平滑可见面）。
     *
     * <p>【1.36】候选只认阶梯方块（{@link EscalatorUtil#isEscalatorStep}）：台阶正上方那块
     * **侧板**的面高正好是台阶 +1.0，下行跨格时会和台阶形成 0.5 : 0.5 的平分而把玩家抬上去，
     * 见类头【1.36】。这个方法是那次修复的第二个（也是更危险的）取块点 ——
     * 真正把脚摆上去的是它，`findEscalatorBelow` 只决定读谁的 `facing/direction`。
     */
    private static double surfaceLineNear(Level level, double refY, double x, double z, double treadPitch) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        int baseY = (int) Math.floor(refY);

        double bestSurface = Double.NaN;
        int bestBlockY = 0;
        double bestDiff = Double.MAX_VALUE;
        for (int dy = -3; dy <= 3; dy++) {
            BlockPos pos = new BlockPos(bx, baseY + dy, bz);
            BlockState state = level.getBlockState(pos);
            if (!EscalatorUtil.isEscalatorStep(state)) continue;
            double surface = surfaceLineOfBlock(state, pos, x, z);
            double diff = Math.abs(surface - refY);
            if (diff < bestDiff) {
                bestDiff = diff;
                bestSurface = surface;
                bestBlockY = pos.getY();
            }
        }
        if (Double.isNaN(bestSurface) || treadPitch <= 0.0) {
            return bestSurface;
        }
        return stairHeight(bestSurface, bestBlockY, treadPitch);
    }

    /**
     * 【1.33】把可见面高度量化成**台阶**：以方块底面为基准，按 {@code pitch} 取
     * 「离可见面最近的那一阶」。可见面本来就落在阶上的方块（FLAT / LANDING 的块顶）
     * 量化后完全不变 ⇒ 平台与平段不受影响，只有斜坡 / 过渡块会变成台阶。
     *
     * <p>【1.38e】{@code pitch} 恒为 {@link #TREAD_PITCH}（0.5）。1.38 曾按本 tick 位移把它砍到
     * 0.25 / 0.125 / 0.0625 —— 那是把台阶本身削小、直接造成「软绵绵」（见类头【1.38e】），
     * 已整条删除。
     */
    private static double stairHeight(double surface, int blockY, double pitch) {
        return blockY + Math.round((surface - blockY) / pitch) * pitch;
    }

    /**
     * MTR 扶梯视觉表面高度（MTR 4.0.5 模型实测）：
     * - FLAT（平直传送带）与 LANDING（上下端平台）：可见顶面 = 块顶 16/16，站立面 = 块顶 +1，
     *   与碰撞箱齐平，不能加 HOVER；
     * - SLOPE（45° 细密齿斜面）：碰撞箱比视觉面高约半格，用 HOVER 防穿模；
     * - TRANSITION_BOTTOM / TRANSITION_TOP（坡段两端的过渡块）：可见顶面同样是块顶，
     *   但必须与相邻坡段的高度对齐，否则平台与坡段交界处会出现半格跳变。
     *   两段都用「坡段同款半格悬浮」的斜率与坡段对齐，并保证脚底始终不低于
     *   台阶碰撞箱顶面，同时收在平台的真实站立高度上。
     */
    private static double surfaceLineOfBlock(BlockState state, BlockPos pos, double x, double z) {
        String orientation = getOrientation(state).toUpperCase();
        double t = progressAlongFacing(state, pos, x, z);
        switch (orientation) {
            case "SLOPE":
                return pos.getY() + t + HOVER;
            case "TRANSITION_BOTTOM":
                // 上坡方向：下坡端接底部平台(块顶 +1)，上坡端接坡段起点(+1.5)
                return pos.getY() + 1.0 + HOVER * t;
            case "TRANSITION_TOP":
                // 上坡方向：与坡段末端(+0.5)对齐，之后升到顶部平台真实站立高度(块顶 +1)。
                // 必须以块顶 +1 为上限截断：台阶碰撞箱在该块上坡半边顶到约 +0.9375，
                // 若照「平台高度」线性降到 +1 会在 t∈(0.5,1) 落进碰撞箱里，
                // 玩家脱离扶梯交还原版物理时会被顶回(被推一下)或直接穿下去。
                return pos.getY() + Math.min(t + HOVER, 1.0);
            default:
                // FLAT / LANDING_TOP / LANDING_BOTTOM：站立面即块顶
                return pos.getY() + 1.0 + FLAT_EPSILON;
        }
    }

    /** 玩家位置沿块 facing（上坡方向）的块内进度，夹到 [0,1]。 */
    private static double progressAlongFacing(BlockState state, BlockPos pos, double x, double z) {
        Direction facing = getFacingProperty(state);
        if (facing == null) return 0;
        double t;
        switch (facing) {
            case NORTH:
                t = pos.getZ() + 1 - z;
                break;
            case SOUTH:
                t = z - pos.getZ();
                break;
            case EAST:
                t = x - pos.getX();
                break;
            case WEST:
                t = pos.getX() + 1 - x;
                break;
            default:
                return 0;
        }
        return Math.max(0.0, Math.min(1.0, t));
    }

    private static Direction getFacingProperty(BlockState state) {
        for (Property<?> prop : state.getProperties()) {
            if ("facing".equals(prop.getName()) && prop.getValueClass() == Direction.class) {
                return (Direction) state.getValue(prop);
            }
        }
        return null;
    }

    private static Boolean escalatorProp(Level level, BlockPos pos, String name) {
        Boolean value = getBooleanProperty(level.getBlockState(pos), name);
        if (value == null) {
            value = getBooleanProperty(level.getBlockState(pos.below()), name);
        }
        return value;
    }

    private static BlockPos[] findEndpointsAlongAxis(Level level, BlockPos start, Direction facing) {
        BlockPos bottom = walkToEndpoint(level, start, facing.getOpposite(), "LANDING_BOTTOM");
        BlockPos top = walkToEndpoint(level, start, facing, "LANDING_TOP");
        return new BlockPos[]{bottom, top};
    }

    /**
     * 沿 {@code dir} 一格一格走，走到**静态踏板**（{@code orientation=landing_*}）就停，
     * 返回踏板**前那一格**（= 最后一个「会动」的方块：阶梯 / 过渡块 / 平段传送带）。
     *
     * <p><b>【1.31】为什么返回前一格而不是踏板本身</b>：扶梯两头的 {@code escalator_step_landing}
     * 是**静态铁板**（没有 {@code #step} 面、纹丝不动），玩家走出阶梯区站到那块板上之后
     * 就不该再被扶梯拖着走。旧版返回踏板 = 把到站目标定在整条链的**最末端**，
     * 于是玩家被一路拖过整块铁板 —— 用户报的「出了阶梯区还在铁板上移动一段距离」。
     *
     * <p>链上没有踏板（例如纯斜坡 / 纯平段传送带）时行为不变：走到头，返回最后一格。
     *
     * <p>【1.35】现在这个方法只用来给「上行 / 下行」的兜底判断提供一个**锚点**
     * （{@code direction} 属性缺失的旧 MTR 才用得上），已经与「到站目标」无关 ——
     * 到站判据变成了「站在静态踏板上就放手」，不需要距离（见类头【1.35】）。
     */
    private static BlockPos walkToEndpoint(Level level, BlockPos start, Direction dir, String landingName) {
        BlockPos current = start;
        BlockPos previous = null;
        for (int i = 0; i < MAX_WALK_LENGTH; i++) {
            BlockPos ahead = current.relative(dir);
            BlockPos next;
            if (EscalatorUtil.isEscalatorStep(level.getBlockState(ahead))) {
                next = ahead;
            } else if (EscalatorUtil.isEscalatorStep(level.getBlockState(ahead.above()))) {
                next = ahead.above();
            } else if (EscalatorUtil.isEscalatorStep(level.getBlockState(ahead.below()))) {
                next = ahead.below();
            } else {
                break;
            }
            previous = current;
            current = next;
            if (landingName.equals(getOrientation(level.getBlockState(current)))) {
                return previous != null ? previous : current;
            }
        }
        return current;
    }

    /**
     * 【1.31】是不是扶梯两头的**静态踏板**（玩家口中的「钢板 / 铁板」：
     * {@code orientation=landing_bottom|landing_top}，模型 {@code mtr:block/escalator_step_landing}，
     * 没有 {@code #step} 面、纹丝不动）。
     *
     * <p><b>【1.35】它就是本类唯一的放手判据</b>（两端对称）：只要玩家脚下这一格是静态踏板，
     * 立刻交还原版物理，不看扶梯朝哪边跑、也不看到站距离。依据是 MTR 自己的物理 ——
     * 钢板碰撞箱满高 16/16，脚踩那格被 {@code checkInsideBlocks} 的 {@code +1e-7} 顶出遍历范围，
     * 所以 MTR **在钢板上从不推人**；只有 15/16 的 {@code flat}/{@code transition} 与斜坡才会送人。
     * 详见类头【1.35】。
     *
     * <p>注意别把它和 {@code flat} 搞混：{@code flat} 用的是带 {@code #step} 面的平段传送带模型，
     * **是会动的**，属于「阶梯区」，仍然照旧被扶梯送人。
     */
    private static boolean isStaticLanding(Level level, BlockPos pos) {
        return getOrientation(level.getBlockState(pos)).startsWith("LANDING");
    }

    private static Boolean getBooleanProperty(BlockState state, String name) {
        for (Property<?> prop : state.getProperties()) {
            if (prop.getName().equalsIgnoreCase(name) && prop.getValueClass() == Boolean.class) {
                return (Boolean) state.getValue(prop);
            }
        }
        return null;
    }

    private static double distanceSq(LivingEntity entity, BlockPos pos) {
        double dx = (pos.getX() + 0.5) - entity.getX();
        double dy = (pos.getY() + 0.5) - entity.getY();
        double dz = (pos.getZ() + 0.5) - entity.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * 找出玩家脚下那一格扶梯方块。
     *
     * <p>【1.32】判据从「从下往上第一个扶梯方块」改成**可见面离脚底最近的那一格**。
     * 原来的写法在坡段上会取错：脚底 = {@code y + t + 0.5}，t 大时 {@code floor(脚底 - 0.2)}
     * 已经落到上一格的层高，于是「从下往上第一个」可能先撞到**下面那格**；而斜坡是台阶式
     * 递升的（每格沿轴升 1 格），不同 y 上各有一块扶梯方块在场 —— 取错那格就会读到相邻格块的
     * {@code facing / direction}，于是这一 tick 往反方向送人，肉眼就是「抽一下」。
     * 按「可见面离脚底最近」挑，恒等于玩家真正踩着的那一格。
     *
     * <p>【1.36】候选方块从「类名含 escalator」收紧成**阶梯方块**
     * （{@link EscalatorUtil#isEscalatorStep}）—— 否则会挑中台阶正上方那块**侧板**，
     * 见类头【1.36】。
     */
    private static BlockPos findEscalatorBelow(Level level, LivingEntity entity) {
        int floorX = (int) Math.floor(entity.getX());
        int floorY = (int) Math.floor(entity.getY() - 0.2);
        int floorZ = (int) Math.floor(entity.getZ());

        double feet = entity.getY();
        BlockPos best = null;
        double bestDiff = Double.MAX_VALUE;
        for (int dy = -2; dy <= 1; dy++) {
            BlockPos check = new BlockPos(floorX, floorY + dy, floorZ);
            BlockState state = level.getBlockState(check);
            if (!EscalatorUtil.isEscalatorStep(state)) continue;
            double diff = Math.abs(surfaceLineOfBlock(state, check, entity.getX(), entity.getZ()) - feet);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = check;
            }
        }
        return best;
    }

    private static BlockPos[] findEscalatorEndpoints(Level level, BlockPos start) {
        Set<BlockPos> visited = new HashSet<>();
        Queue<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        visited.add(start);

        BlockPos bottomLanding = null;
        BlockPos topLanding = null;
        BlockPos lowest = start;
        BlockPos highest = start;

        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            BlockState state = level.getBlockState(pos);
            String orientation = getOrientation(state);

            if ("LANDING_BOTTOM".equals(orientation)) {
                bottomLanding = pos;
            } else if ("LANDING_TOP".equals(orientation)) {
                topLanding = pos;
            }

            if (pos.getY() < lowest.getY()) lowest = pos;
            if (pos.getY() > highest.getY()) highest = pos;

            for (Direction dir : Direction.values()) {
                BlockPos neighbor = pos.relative(dir);
                if (!visited.contains(neighbor) && EscalatorUtil.isEscalatorStep(level.getBlockState(neighbor))) {
                    visited.add(neighbor);
                    queue.add(neighbor);
                }
            }
        }

        if (bottomLanding != null) lowest = bottomLanding;
        if (topLanding != null) highest = topLanding;

        return new BlockPos[]{lowest, highest};
    }

    private static String getOrientation(BlockState state) {
        for (Property<?> prop : state.getProperties()) {
            if ("orientation".equals(prop.getName())) {
                Object value = state.getValue(prop);
                if (value != null) {
                    return value.toString().toUpperCase();
                }
            }
        }
        return "";
    }
}
