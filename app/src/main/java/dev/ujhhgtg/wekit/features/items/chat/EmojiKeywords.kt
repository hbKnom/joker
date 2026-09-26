package dev.ujhhgtg.wekit.features.items.chat

/**
 * 「制作表情」关键词表 —— 完整照搬原脚本，未做任何增删。
 *
 * - FREE_SINGLE / FREE_DUAL：自由表情源（ovoav），请求用 freeKeyword(key) 反查出的中文 msg。
 * - EQUAL_SINGLE / EQUAL_DUAL：平等表情源（apix.iqfk.top），请求用 meme（英文 id）。
 * - EQUAL_SV2_MEMES：需要走 SV2 接口的 meme 集合（双人时 url1/url2 且带 Bearer 头）。
 */
object EmojiKeywords {

    /** 自由源单人关键词：display -> key */
    val FREE_SINGLE: List<Pair<String, String>> = listOf(
        "吃下" to "chi_xia", "拍拍" to "pai_pai", "捣鼓" to "dao_gu",
        "膜拜" to "mo_bai", "吃掉" to "chi_diao", "贴贴" to "tie_tie",
        "亲亲" to "qin_qin", "摇一摇" to "yao_yao", "摸摸" to "mo_mo",
        "转圈" to "zhuan_quan", "手转" to "shou_zhuan", "推车" to "tui_che",
        "反了" to "fan_le", "木鱼" to "mu_yu", "撕墙纸" to "si_qiang_zhi",
        "紧贴" to "jin_tie", "捶爆" to "chui_bao", "锤头" to "chui_tou",
        "抛掷" to "pao_zhi", "望远镜" to "wang_yuan_jing", "拿捏" to "na_nie",
        "吸嗦" to "xi_suo", "跳舞" to "tiao_wu", "踩" to "cai",
        "蜘蛛" to "zhi_zhu", "挠头" to "nao_tou", "撕衣服" to "si_yi_fu",
        "开导" to "kai_dao", "追火车" to "zhui_huo_che",
    )

    /** 自由源双人关键词：display -> key */
    val FREE_DUAL: List<Pair<String, String>> = listOf(
        "艹你" to "cao_ni", "揍你" to "zou_ni", "踢你" to "ti_ni",
        "抱你" to "bao_ni", "蹭你" to "ceng_ni",
    )

    /** 自由源 key -> 请求 msg（原脚本 freeKeyword 反查） */
    val FREE_KEYWORD_MSG: Map<String, String> = mapOf(
        "chi_xia" to "吃下", "pai_pai" to "拍拍", "dao_gu" to "捣鼓",
        "mo_bai" to "膜拜", "chi_diao" to "吃掉", "tie_tie" to "贴贴",
        "qin_qin" to "啾啾", "yao_yao" to "摇一摇", "mo_mo" to "摸摸",
        "zhuan_quan" to "转", "shou_zhuan" to "手转", "tui_che" to "推车",
        "fan_le" to "反了", "mu_yu" to "木鱼、敲木鱼", "si_qiang_zhi" to "撕墙纸",
        "jin_tie" to "紧贴", "chui_bao" to "捶爆", "chui_tou" to "锤头",
        "pao_zhi" to "抛、掷", "wang_yuan_jing" to "望远镜", "na_nie" to "拿捏、戏弄",
        "xi_suo" to "吸、嗦、吃掉", "tiao_wu" to "跳舞、火柴人跳舞", "cai" to "踩",
        "zhi_zhu" to "蜘蛛爬、小蜘蛛踩", "nao_tou" to "挠头", "si_yi_fu" to "撕衣服",
        "kai_dao" to "床上导图、导图", "zhui_huo_che" to "追列车、追火车、追车",
        "cao_ni" to "猫猫艹图、猫猫双图、草", "zou_ni" to "揍、打屁股",
        "ti_ni" to "走过来踢、踢", "bao_ni" to "抱、抱抱", "ceng_ni" to "蹭、蹭蹭",
    )

    /** 平等源单人关键词：display -> meme */
    val EQUAL_SINGLE: List<Pair<String, String>> = listOf(
        "吃下" to "eat", "拍拍" to "pat", "捣鼓" to "pound",
        "膜拜" to "worship", "吃掉" to "eat", "贴贴" to "tightly",
        "摸摸" to "petpet", "转圈" to "turn", "手转" to "turn",
        "反了" to "upside_down", "木鱼" to "wooden_fish", "撕墙纸" to "rip_clothes",
        "撕衣服" to "rip_clothes", "紧贴" to "tightly", "捶爆" to "thump_wildly",
        "锤头" to "hammer", "抛掷" to "throw_gif", "拿捏" to "pinch",
        "吸嗦" to "suck", "跳舞" to "stickman_dancing", "踩" to "step_on",
        "蜘蛛" to "spider", "挠头" to "scratch_head", "开导" to "masturbate",
        "追火车" to "chase_train", "和维里奈合影" to "kurogames_verina_group_photo",
        "家人们谁懂啊" to "family_know", "卡提希娅抬脚" to "kurogames_cartethyia_feetup",
        "最想要的东西" to "what_he_wants", "怎么说话的你" to "beat_head",
        "满脑子都是它" to "fill_head", "今汐小龙包" to "kurogames_jinhsi_steamed_buns",
        "米学长手机" to "mihoyo_senior_phone", "漂泊头像框" to "kurogames_rover_head",
        "out" to "out", "猫抓猫猫抓" to "cat_scratch", "亚托莉喜欢" to "atri_like",
        "土豆地雷" to "potato_mines", "球面旋转" to "sphere_rotate", "格蕾修画" to "painter",
        "让我进去" to "let_me_in", "咖波爱心" to "capoo_love", "三维旋转" to "rotate_3d",
        "胡桃平板" to "walnut_pad", "源石封印" to "seal", "第一可爱" to "sekaiichi_kawaii",
        "灰飞烟灭" to "fade_away", "不要靠近" to "dont_go_near", "坟前比耶" to "tomb_yeah",
        "紧紧贴着" to "tightly", "急急国王" to "jiji_king", "这像画吗" to "paint",
        "秧秧老公" to "kurogames_yangyang_lover", "你不懂啦" to "dont_get",
        "宝宝是我" to "baby", "指指点点" to "zzdd", "榴莲坤头" to "ikun_durian_head",
        "坤坤喜欢" to "ikun_like", "你干嘛哟" to "ikun_why_are_you", "蜜雪冰城" to "mixue",
        "荣耀之丘" to "kurogames_rover_cards", "抽卡非酋" to "kurogames_changli_finger",
        "窃窃私语" to "whisper", "灰姑娘吃" to "cinderella_eat", "亚托莉指" to "atri_finger",
        "毒瘾发作" to "addiction", "飞廉之猩" to "kurogames_orang", "柴郡点赞" to "azur_lane_cheshire_thumbs_up",
        "我勒个豆" to "peas", "什么东西" to "something", "宁宁困惑" to "yuzu_soft_ayachi_nene",
        "夜兰手机" to "mihoyo_yelan_phone", "付费观看" to "pay_to_watch", "咖波砸蛋" to "capoo_smash_egg",
        "弗洛洛吃" to "kurogames_phrolova_eat", "漂泊者舔" to "kurogames_rover_lick",
        "燃起来了" to "ignite", "骑龙王" to "qilongwang", "今汐坐" to "kurogames_jinhsi_sit",
        "新年好" to "happy_new_year", "不要脸" to "buyaolian", "卖掉了" to "sold_out",
        "咖波打" to "capooplay", "露帕吃" to "kurogames_lupa_eat", "草神啃" to "nahida_bite",
        "金字塔" to "pyramid", "炖群友" to "stew", "玩游戏" to "play_game",
        "汤姆笑" to "tom_tease", "回旋转" to "swirl_turn", "科目三" to "subject3",
        "体温枪" to "thermometer_gun", "鸡符咒" to "this_chicken", "洗衣机" to "washer",
        "风车转" to "windmill_turn", "你不懂" to "you_dont_get", "抱大腿" to "hug_leg",
        "哈哈镜" to "funny_mirror", "棒棒糖" to "lick_candy", "冰红茶" to "ice_tea_head",
        "可莉吃" to "klee_eat", "追火车" to "chase_train", "咖波贴" to "capoo_rub",
        "字符画" to "charpic", "盯着你" to "stare_at_you", "像素化" to "pixelate",
        "打棒球" to "play_baseball", "坐得住" to "sit_still", "大傻椿" to "kurogames_camellya_photo",
        "小黑子" to "ikun_head", "卖身契" to "contract", "火柴鹿" to "huochailu",
        "垃圾桶" to "garbage", "尤诺抱" to "kurogames_iuno_hug", "看情书" to "read_love_letters",
        "疾风鹿" to "chillet_deer", "七七舔" to "mihoyo_qiqi_suck", "今汐吃" to "kurogames_jinhsi_eat",
        "验孕棒" to "pregnancy_test", "未亡人" to "widow", "兑换券" to "coupon",
        "后空翻" to "backflip", "打篮球" to "play_basketball", "听音乐" to "listen_music",
        "无响应" to "no_response", "笨死了" to "myplay", "爆炒你" to "caosini",
        "电死你" to "electrify_you", "付款码" to "payment_code", "敬礼喵" to "yesirmiao",
        "追杀你" to "zhuishamiao", "斯科特" to "sikete", "叠罗汉" to "dieluohan",
        "你再说" to "nizaishuo", "橘子头" to "orange_head", "看烟花" to "fireworks_head",
        "睡梦中" to "miss_in_my_sleep", "露帕指" to "kurogames_lupa_photo", "伍六七" to "scissor_seven_head",
        "折枝画" to "kurogames_zhezhi_draw", "阿布哭" to "kurogames_abby_weeping",
        "看看腿" to "look_leg", "咖波掏" to "capoo_fished_out", "Ciallo" to "yuzu_soft_ciallo",
        "doro鸭" to "doroya", "doro点赞" to "doro", "doro 爱" to "doro_dear",
        "doro 舔" to "doro_lick", "doro 外卖" to "dorowaimai", "扔石" to "throwing_poop",
        "钓鱼" to "fishing", "符咒" to "rune", "放屁" to "fart", "在想" to "think_what",
        "致电" to "you_should_call", "红温" to "flush", "弹你" to "flick",
        "闪瞎" to "flash_blind", "嘲笑" to "taunt", "抓走" to "time_to_go",
        "上香" to "mourning", "加载" to "loading", "踢球" to "kick_ball",
        "抱哭" to "hold_tight", "关注" to "follow", "爆捶" to "thump_wildly",
        "怒撕" to "rip_angrily", "鼓掌" to "applaud", "斩首" to "behead",
        "震惊" to "shock", "收养" to "adoption", "挚爱" to "anyliew_people_i_like",
        "挣扎" to "anyliew_struggling", "求我" to "begged_me", "洗了" to "xile",
        "奶龙" to "pinailong", "捶你" to "chuini", "小强" to "cockroaches",
        "迷你" to "dinosaur_head", "怼地" to "duidi", "发烧" to "fever",
        "篮球" to "ikun_basketball", "原批" to "mihoyo_genshin_impact_players",
        "需要" to "need", "别碰" to "dont_touch", "艾特" to "why_at_me",
        "支柱" to "support", "小丑" to "clown_mask", "鄙视" to "mahiro_fuck",
        "晚安" to "kurogames_good_night", "滑稽" to "clownish", "小手" to "small_hands",
        "一样" to "alike", "添乱" to "add_chaos", "群青" to "cyan",
        "墙纸" to "wallpaper", "震动" to "vibrate", "讲课" to "teach",
        "无语" to "speechless", "砸碎" to "smash", "看书" to "read_book",
        "晃脑" to "shake_head", "打拳" to "punch", "土豆" to "potato",
        "撕开" to "rip_clothes", "鸣批" to "kurogames_mp", "催眠" to "saimin_app",
        "诈尸" to "rise_dead", "快逃" to "run_away", "丢猫" to "diucat",
        "喷射" to "penshe", "嘴你" to "zuini", "嘿壳" to "heike",
        "日出" to "richu", "列队" to "liedui", "榴莲" to "durian",
        "下班" to "downban", "坤坤" to "ikun_need_tv", "龙手" to "dragon_hand",
        "死刑" to "mihoyo_funina_death_penalty", "人机" to "mihoyo_ineffa_droid",
        "忠诚" to "zhogncheng", "摸鱼" to "slacking_off", "骑马" to "horse_riding",
        "躺撅" to "laydown_do", "坐撅" to "sitdown_do", "吸" to "suck",
        "转" to "turn", "吃" to "eat", "甩" to "shuai", "抛" to "throw_gif",
        "爬" to "crawl", "捣" to "pound", "顶" to "play", "摸" to "petpet",
        "拍" to "pat", "敲" to "knock", "锤" to "hammer", "捶" to "thump",
        "扔" to "throw", "踩" to "step_on", "撕" to "rip", "炖" to "capoo_stew",
        "盯" to "jerry_stare", "滚" to "roll", "撞" to "capoo_strike",
        "拜" to "worship", "舔" to "shiroko_pero", "捏" to "pinch",
        "导" to "masturbate", "跳" to "jump", "ok" to "ok",
        "肥仔网络皇帝" to "feizhaiking", "职业选手" to "zhiyexuanshou",
        "你是二次元" to "erciyuan",
    )

    /** 平等源双人关键词：display -> meme */
    val EQUAL_DUAL: List<Pair<String, String>> = listOf(
        "艹你" to "caosini", "揍你" to "chuini", "踢你" to "kick_ball",
        "抱你" to "hold_tight", "蹭你" to "capoo_rub",
        "LY-1舰载激光武器" to "ly01", "小掘" to "little_do", "超市" to "do",
        "看看你的" to "can_can_need", "口" to "oral_sex", "鞭打" to "lash",
        "击剑" to "fencing", "抱抱" to "hug", "棒打鲜橙" to "mixue_stick_beaten_fresh_orange",
        "茉莉奶绿" to "mixue_jasmine_milk_green", "揍" to "beat_up", "贴" to "rub",
        "猪猪车" to "pigcar", "doro锤" to "dorochui", "吉伊卡哇" to "chiikawa",
        "男铜" to "nantongjue", "女铜" to "nvtongjue", "骑" to "qi",
    )

    /** 平等源需要走 SV2 接口的 meme 集合 */
    val EQUAL_SV2_MEMES: Set<String> = setOf(
        "ly01", "little_do", "do", "can_can_need", "oral_sex", "lash", "fencing", "hug",
        "mixue_stick_beaten_fresh_orange", "mixue_jasmine_milk_green", "beat_up", "rub", "pigcar",
        "dorochui", "chiikawa", "nantongjue", "nvtongjue", "qi",
    )
}
