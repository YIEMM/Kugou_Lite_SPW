package cn.spw.kugou.lite.sidecar

import cn.spw.kugou.lite.diag.Diag

/**
 * 私人 FM 会话状态：档位、续流游标、已听去重、提前补货、反馈上报。
 *
 * ## 与 MD3Music 的对应关系
 *
 * 参考 `lib/modules/personal_fm/personal_fm_core.dart`：
 * - `kFmStations` → [stations]（下标固定，配置里按下标持久化，不可挪动）
 * - `curMark` → [Session.curMark]（上游随 `song_list` 带回，下一轮原样带上）
 * - `seen` → [Session.seenHashes]（按 hash 去重）
 * - `kFmPrefetchThreshold = 3` → [PREFETCH_THRESHOLD]
 * - `reportFmFeedback` / `dislikeFmTrack` → [feedback]
 *
 * ## 分层
 *
 * 本对象只维护会话状态与发起请求；**播放动作**由 UI 侧调用
 * `playSongsFromQueue` 完成，不在这里碰播放控制器。
 */
object PersonalFm {

    private const val TAG = "personal-fm"

    /** 队列剩余不多于这个数就提前补货（与 MD3Music 一致）。 */
    const val PREFETCH_THRESHOLD = 3

    /** 一个具名电台：把 API 的 (mode, songPoolId) 包成用户看得懂的一档。 */
    data class Station(
        val label: String,
        val mode: String,
        val songPoolId: Int,
    )

    /**
     * 档位表。**下标不可变动** —— 下标同时持久化在 `DiscoverConfig.fmStationIndex`，
     * 一旦挪动，老用户保存的档位就会错位。
     */
    val stations: List<Station> = listOf(
        Station(label = "红心", mode = "normal", songPoolId = 0),
        Station(label = "探索", mode = "normal", songPoolId = 2),
        Station(label = "小众", mode = "small", songPoolId = 1),
        Station(label = "电台", mode = "radio", songPoolId = 0),
    )

    fun needsRefill(remaining: Int): Boolean = remaining <= PREFETCH_THRESHOLD

    /**
     * 一次 FM 会话。
     *
     * @param stationIndex 当前档位下标
     */
    class Session(val stationIndex: Int) {

        /**
         * 续流游标：**上一首**的歌曲信息；新会话为空。
         *
         * ## 为什么不是 cur_mark
         *
         * 真机日志显示上游每次返回同一个 `cur_mark=99999998`，拿它当分页游标
         * 会导致每次续拉都拿到同一批歌，第二次就被去重全挡（「无限续播」只能
         * 成功一次）。对照 MD3Music：续流靠上一首的 hash + songid。
         * `cur_mark` 是**每首歌的反馈令牌**（play/garbage 上报用）。
         */
        var lastSong: Song? = null

        /** 已听过的 hash，用于补货去重。 */
        val seenHashes: MutableSet<String> = mutableSetOf()

        /** 把一批已听的歌记入去重集合。 */
        fun markPlayed(songs: List<Song>) {
            songs.forEach { s ->
                val h = s.hash.ifBlank { s.albumAudioId }
                if (h.isNotBlank()) seenHashes.add(h)
            }
        }

        /**
         * 过滤出没听过的歌，并顺手把它们记入已听集合。
         *
         * 整批都重复时返回空列表 —— 这是**正常成功**，不是失败；
         * 调用方不应据此报错或清空现有队列。
         */
        fun filterFresh(batch: List<Song>): List<Song> {
            val fresh = batch.filter { s ->
                val h = s.hash.ifBlank { s.albumAudioId }
                h.isNotBlank() && h !in seenHashes
            }
            markPlayed(fresh)
            return fresh
        }

        /**
         * 切换档位：开一个全新会话，清空游标与去重集合。
         *
         * 不清空的话，旧档位的 `cur_mark` 会被带到新档位，
         * 上游会按错误的推荐流继续返回。
         */
        fun switchStation(index: Int): Session =
            Session(index.coerceIn(0, stations.lastIndex))
    }

    /**
     * 取一批 FM 歌曲。
     *
     * @return [FmBatch]；解析失败时上游已保证返回空批次，不会是 null
     */
    fun load(stationIndex: Int, curMark: String?): FmBatch =
        DiscoverApi.personalFm(stationIndex, curMark)

    /**
     * 上报 FM 反馈（如 `garbage` = 不喜欢）。
     *
     * ## 失败不阻塞
     *
     * 反馈只是一次 ping：请求失败只记日志，绝不抛异常、不影响播放推进。
     * 这是参考实现里明确钉死的口径（`reportFmFeedback` 用 try/catch 兜住）。
     *
     * @param action 反馈动作，如 `garbage`
     * @param song 被反馈的歌
     * @param stationIndex 当前档位
     * @param playtimeSec 已播放秒数，可空
     */
    fun feedback(
        action: String,
        song: Song,
        stationIndex: Int,
        playtimeSec: Int? = null,
    ) {
        val station = stations.getOrElse(stationIndex) { stations[0] }
        val params = buildMap {
            put("action", action)
            put("mode", station.mode)
            put("song_pool_id", station.songPoolId.toString())
            put("hash", song.hash)
            if (song.curMark.isNotBlank()) put("cur_mark", song.curMark)
            if (playtimeSec != null) put("playtime", playtimeSec.toString())
        }
        try {
            KugouApi.getRaw("/personal/fm", params)
            Diag.info(TAG, "反馈 action=$action hash=${song.hash.take(8)} 已上报")
        } catch (e: Exception) {
            Diag.warn(TAG, "反馈上报失败（不影响播放）：${e.message}")
        }
    }
}
