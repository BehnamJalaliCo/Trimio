package io.trimio.core.model.style

import kotlinx.serialization.Serializable

/**
 * Rendering engine family. Styles in one family share the same render technique,
 * so adding a style is mostly data (tokens, presets, shaders), not new engine code.
 */
@Serializable
enum class StyleFamily(val nameFa: String, val nameEn: String) {
    Vector2D("وکتور دوبعدی", "2D Vector"),
    ShaderFx("شیدر و افکت", "Shader FX"),
    Typography("تایپوگرافی", "Typography"),
    TextureCollage("بافت و کلاژ", "Texture & Collage"),
    ThreeD("سه‌بعدی", "3D"),
    Organic("ارگانیک", "Organic"),
}

/** Minimum device tier able to render a style in real time for preview. */
@Serializable
enum class RenderCost { Light, Medium, Heavy }

@Serializable
enum class DesignStyle(
    /** Stable id used in style packs, timelines and URLs. Never rename. */
    val id: String,
    val nameFa: String,
    val nameEn: String,
    val family: StyleFamily,
    val cost: RenderCost,
) {
    Skeuomorphism("skeuomorphism", "اسکیومورفیسم", "Skeuomorphism", StyleFamily.TextureCollage, RenderCost.Medium),
    Neomorphism("neomorphism", "نئومورفیسم", "Neomorphism", StyleFamily.ShaderFx, RenderCost.Light),
    Glassmorphism("glassmorphism", "گلس‌مورفیسم", "Glassmorphism", StyleFamily.ShaderFx, RenderCost.Medium),
    Claymorphism("claymorphism", "کلی‌مورفیسم", "Claymorphism", StyleFamily.ThreeD, RenderCost.Heavy),
    Minimalism("minimalism", "مینیمالیسم", "Minimalism", StyleFamily.Vector2D, RenderCost.Light),
    Maximalism("maximalism", "مکسیمالیسم", "Maximalism", StyleFamily.Vector2D, RenderCost.Medium),
    Brutalism("brutalism", "بروتالیسم", "Brutalism", StyleFamily.Vector2D, RenderCost.Light),
    Neobrutalism("neobrutalism", "نئوبروتالیسم", "Neobrutalism", StyleFamily.Vector2D, RenderCost.Light),
    LiquidGlass("liquid-glass", "لیکوئید گلس", "Liquid Glass", StyleFamily.ShaderFx, RenderCost.Heavy),
    BentoGrid("bento-grid", "بنتو گرید", "Bento Grid", StyleFamily.Vector2D, RenderCost.Light),
    SpatialUi("spatial-ui", "اسپیشال یو‌آی", "Spatial UI", StyleFamily.ThreeD, RenderCost.Heavy),
    AuroraUi("aurora-ui", "آئورورا یو‌آی", "Aurora UI", StyleFamily.ShaderFx, RenderCost.Medium),
    KineticTypography("kinetic-typography", "کینتیک تایپوگرافی", "Kinetic Typography", StyleFamily.Typography, RenderCost.Light),
    Retrofuturism("retrofuturism", "رترو فیوچریسم", "Retrofuturism", StyleFamily.TextureCollage, RenderCost.Medium),
    Y2k("y2k", "وای‌توکی", "Y2K", StyleFamily.TextureCollage, RenderCost.Medium),
    ChromeLiquidMetal("chrome-liquid-metal", "کروم / لیکوئید متال", "Chrome / Liquid Metal", StyleFamily.ShaderFx, RenderCost.Heavy),
    DarkModeUi("dark-mode-ui", "دارک مود یو‌آی", "Dark Mode UI", StyleFamily.Vector2D, RenderCost.Light),
    FlatDesign("flat-design", "فلت دیزاین", "Flat Design", StyleFamily.Vector2D, RenderCost.Light),
    MaterialDesign("material-design", "متریال دیزاین", "Material Design", StyleFamily.Vector2D, RenderCost.Light),
    AntiDesign("anti-design", "آنتی‌دیزاین", "Anti-Design", StyleFamily.Vector2D, RenderCost.Light),
    GrainNoise("grain-noise", "گرین و نویز", "Grain & Noise", StyleFamily.ShaderFx, RenderCost.Medium),
    Scrapbook("scrapbook", "اسکرپ‌بوک", "Scrapbook UI", StyleFamily.TextureCollage, RenderCost.Medium),
    StickerUi("sticker-ui", "استیکر یو‌آی", "Sticker UI", StyleFamily.TextureCollage, RenderCost.Light),
    BiophilicDesign("biophilic-design", "بیوفیلیک دیزاین", "Biophilic Design", StyleFamily.Organic, RenderCost.Medium),
    ThreeDImmersive("3d-immersive", "تری‌دی ایمرسیو", "3D & Immersive", StyleFamily.ThreeD, RenderCost.Heavy),
    MotionDrivenUi("motion-driven-ui", "موشن‌دریون یو‌آی", "Motion-Driven UI", StyleFamily.Vector2D, RenderCost.Light),
    ExperimentalTypography("experimental-typography", "اکسپریمنتال تایپوگرافی", "Experimental Typography", StyleFamily.Typography, RenderCost.Medium),
    OrganicUi("organic-ui", "اورگانیک یو‌آی", "Organic UI", StyleFamily.Organic, RenderCost.Medium);

    companion object {
        fun fromId(id: String): DesignStyle? = entries.firstOrNull { it.id == id }
    }
}
