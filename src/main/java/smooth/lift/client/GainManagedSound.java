package smooth.lift.client;

/**
 * 【1.20】「增益由本模组自己管、需要放开原版 [0,1] 夹取」的声音标记。
 *
 * <p>原版 {@code SoundEngine.calculateVolume(SoundInstance)} 等价于
 * {@code Mth.clamp(实例音量 × 该声音来源的滑块音量, 0, 1)}，所以界面/指令里 >100 的音量
 * 必须靠 {@link smooth.lift.client.mixin.SoundEngineVolumeMixin} 放行到
 * {@link EscalatorAudioPlayer#MAX_GAIN}（= 10×）。而那个 Mixin 得先认出「这是我方的声音」。
 *
 * <p><b>这里踩过坑（1.19 修复）</b>：早先 Mixin 走的判定只看
 * {@link EscalatorAudioPlayer} 的运行底噪实例，把
 * {@link EscalatorChimePlayer} 的无障碍提示音漏掉了 ——
 * 于是「提示音音量」调过 100 完全没有变化，表现就是「无障碍提示音最多只能调到 100」。
 * 现在两个播放器的实例都实现本接口，Mixin 统一按接口判定，再也不会漏。
 *
 * <p>注意光放开 Mixin 还不够：OpenAL 还会把**源增益**夹到 {@code AL_MAX_GAIN}
 * （默认 = 1.0），所以实例开播时还要把该属性抬到 {@code EscalatorAudioPlayer.MAX_GAIN}。
 * 这两件事（放行 Mixin + 抬 AL_MAX_GAIN）必须成对做，缺一个都还是「最多 1.0×」。
 */
public interface GainManagedSound {
}
