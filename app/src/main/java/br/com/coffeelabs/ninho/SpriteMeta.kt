package br.com.coffeelabs.ninho

/** Sprites recortados da sprite sheet do dragão: tamanho, lado para onde olham e posição da cabeça (px do sprite). */
object SpriteMeta {
    class Info(val name: String, val w: Int, val h: Int, val face: Int, val headX: Float, val headY: Float)

    val ALL = listOf(
        Info("egg_idle", 262, 252, 1, 131.0f, 126.0f),
        Info("egg_wobble", 254, 251, 1, 127.0f, 125.5f),
        Info("egg_crack", 259, 251, 1, 129.5f, 125.5f),
        Info("adult_roar", 360, 363, -1, 78.0f, 88.0f),
        Info("adult_open", 336, 359, 1, 235.0f, 119.5f),
        Info("adult_idle", 329, 336, 1, 222.4f, 124.3f),
        Info("baby_crouch", 201, 143, 1, 144.0f, 62.0f),
        Info("baby_walk", 230, 164, 1, 168.3f, 75.2f),
        Info("child_stand", 274, 181, 1, 211.3f, 74.5f),
        Info("child_hop", 255, 201, 1, 198.3f, 58.8f),
        Info("adult_coil1", 323, 323, 1, 232.0f, 116.9f),
        Info("adult_coil2", 349, 275, -1, 94.1f, 119.0f),
        Info("adult_coil3", 331, 322, 1, 236.1f, 116.4f),
        Info("baby_sit", 229, 168, 1, 168.0f, 65.0f),
        Info("child_jump", 242, 196, 1, 183.1f, 39.8f),
        Info("child_sparkle", 304, 217, 1, 199.0f, 70.0f),
        Info("teen_swim", 855, 345, -1, 90.7f, 111.9f),
        Info("adult_coil4", 421, 322, 1, 328.4f, 120.4f),
        Info("adult_coil5", 346, 312, 1, 255.1f, 121.6f),
        Info("elder_swim", 770, 336, -1, 90.0f, 127.1f),
        Info("elder_coil", 427, 320, 1, 338.0f, 183.0f),
        Info("elder_fade1", 420, 318, 1, 348.0f, 224.0f),
        Info("elder_fade2", 369, 291, 1, 296.0f, 227.4f),
        Info("baby_crouch_sleep", 201, 143, 1, 144.0f, 62.0f),
        Info("baby_sit_sleep", 229, 168, 1, 168.0f, 65.0f),
        Info("child_stand_sleep", 274, 181, 1, 211.3f, 74.5f),
        Info("adult_coil5_sleep", 346, 312, 1, 255.1f, 121.6f),
        Info("elder_coil_sleep", 427, 320, 1, 338.0f, 183.0f)
    )
}
