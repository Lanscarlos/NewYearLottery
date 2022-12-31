package top.lanscarlos.lottery

import ink.ptms.adyeshach.core.Adyeshach
import ink.ptms.adyeshach.core.AdyeshachHologram
import me.asgard.sacreditem.api.SacredItemAPI
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.command.CommandSender
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Player
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import taboolib.common.LifeCycle
import taboolib.common.platform.Awake
import taboolib.common.platform.command.command
import taboolib.common.platform.event.SubscribeEvent
import taboolib.common.platform.function.info
import taboolib.common.platform.function.submitAsync
import taboolib.common.util.Location
import taboolib.common.util.unsafeLazy
import taboolib.common5.Baffle
import taboolib.common5.cint
import taboolib.module.chat.colored
import taboolib.module.configuration.*
import taboolib.module.configuration.util.getLocation
import taboolib.module.configuration.util.setLocation
import taboolib.module.kether.isInt
import taboolib.platform.util.*
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * NewYearLottery
 * top.lanscarlos.lottery
 *
 * 连杀奖励
 *
 * @author Lanscarlos
 * @since 2022-12-30 21:57
 */
object MultiKillingReward {

    @Config("multi-killing-reward.yml")
    lateinit var config: Configuration
        private set

    @ConfigNode("top-setting.item", "multi-killing-reward.yml")
    var topItem = Material.GLOWSTONE.name

    @ConfigNode("top-setting.title", "multi-killing-reward.yml")
    var topTitle = "&e「 &6新年击杀大腕儿榜 &e」"

    @ConfigNode("top-setting.format", "multi-killing-reward.yml")
    lateinit var topFormat: List<String>

    @ConfigNode("reward", "multi-killing-reward.yml")
    val reward = ConfigNodeTransfer<Any?, Map<Int, List<String>>> {
        val section = this as? ConfigurationSection ?: return@ConfigNodeTransfer mapOf()
        val map = mutableMapOf<Int, List<String>>()

        for (key in section.getKeys(false)) {
            if (!key.isInt()) continue
            map[key.toInt()] = section.getStringList(key)
        }

        return@ConfigNodeTransfer mapOf()
    }

    val baffle = Baffle.of(600 * 50L, TimeUnit.MILLISECONDS)

    val killingData by unsafeLazy { createLocal("killing.json", type = Type.FAST_JSON) }

    val holograms = mutableMapOf<Player, AdyeshachHologram>()

    /* 连杀数缓存 */
    val record = mutableMapOf<Player, Int>()

    @Awake(LifeCycle.ENABLE)
    fun onEnable() {

        // 读取击杀数
        for (uuid in killingData.getKeys(false)) {
            if (uuid.startsWith('@')) continue
            val player = Bukkit.getPlayer(UUID.fromString(uuid)) ?: continue
            record[player] = killingData.getInt(uuid, 0)
        }

        command("mutilkillingreward", aliases = listOf("mkr")) {
            literal("reload") {
                execute<CommandSender> { _, _, _ ->
                    updateHologram()
                }
            }
            literal("top") {
                execute<Player> { sender, _, _ ->
                    killingData.setLocation("@Top", sender.location.toProxyLocation())
                    updateHologram()
                }
            }
        }
    }

    fun updateHologram(player: Player? = null) {
        val hologramHandler = Adyeshach.api().getHologramHandler()
        val items = arrayListOf<AdyeshachHologram.Item>()
        items += hologramHandler.createHologramItem(ItemStack(Material.valueOf(topItem.uppercase())), space = 0.5)
        items += hologramHandler.createHologramItem(topTitle.colored(), space = 0.4)

        // 击杀数排序
        val topData = record.toList().sortedByDescending { it.second }

        for ((index, it) in topData.withIndex()) {
            if (index >= topFormat.size) break

            val line = topFormat[index].replace("{0}", it.first.name).replace("{1}", it.second.toString())
            items += hologramHandler.createHologramItem(line.colored(), space = 0.4)
        }

        val loc = getTopLocation().toBukkitLocation()


        if (player != null) {
            if (player in holograms) {
                info("player ${player.name} hologram in cache.")
                val holo = holograms[player]!!
                holo.update(items)
                holo.teleport(loc)
            } else {
                info("player ${player.name} hologram not in cache.")
                holograms[player] = hologramHandler.createHologram(player, loc, items)
            }
        } else {
            Bukkit.getOnlinePlayers().forEach {
                if (it in holograms) {
                    info("player ${it.name} hologram in cache.")
                    val holo = holograms[it]!!
                    holo.update(items)
                    holo.teleport(loc)
                } else {
                    info("player ${it.name} hologram not in cache.")
                    holograms[it] = hologramHandler.createHologram(it, loc, items)
                }
            }
        }
    }

    fun getTopLocation(): Location {
        return killingData.getLocation("@Top") ?: Location("world", -118.5, 60.0, 92.5).also {
            killingData.setLocation("@Top", it)
        }
    }

    @SubscribeEvent
    fun e(e: PlayerJoinEvent) {
        submitAsync { updateHologram(e.player) }
    }

    @SubscribeEvent
    fun e(e: PlayerQuitEvent) {
        baffle.reset(e.player.name)
    }

    @SubscribeEvent
    fun e(e: PlayerDeathEvent) {
        // 重置被杀者的连杀数
        e.entity.removeMeta("killing")

        // 击杀者连杀数处理
        val killer = e.killer as? Player ?: return

        val count = if (killer.hasMeta("killing")) {
            killer.getMetaFirst("killing").cast<Int>() + 1
        } else {
            1
        }

        // 检查是否在规定时间内连杀
        if (baffle.hasNext(killer.name)) {
            // 已超时
            info("player ${killer.name} 本次击杀已超时 $count -> 0")
            killer.setMeta("killing", 0)
            return
        } else {
            baffle.next(killer.name)
            killer.setMeta("killing", count)
            info("player ${killer.name} 本次击杀未超时 $count")
        }

        // 给予奖励
        this.reward.get()[count]?.let { rewards ->
            killer.giveItem(rewards.mapNotNull {
                SacredItemAPI.getItem(it)?.getItemStack(killer)
            })
        }

        val record = this.record.computeIfAbsent(killer) { 0 }

        info("record -> $record")
        info("count -> $count")


        if (count > record) {
            info("player ${killer.name} 刷新击杀记录 $record -> $count")

            // 刷新记录
            this.record[killer] = count

            // 刷新榜单
            updateHologram()

            // 存入文件
            killingData[killer.uniqueId.toString()] = count
        } else {
            info("player ${killer.name} 未能刷新击杀记录 $record -> $count")
        }
    }
}