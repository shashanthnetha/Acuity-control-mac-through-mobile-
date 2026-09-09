import os
import subprocess
from PIL import Image, ImageDraw

def draw_focus_frame(size, bg_color=(17, 21, 29, 255), fg_color=(77, 208, 255, 255), is_round=False, base_radius=0.22):
    scale = 4
    s = size * scale
    img = Image.new('RGBA', (s, s), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    
    # Base background
    if bg_color is not None:
        if is_round:
            draw.ellipse([(0, 0), (s - 1, s - 1)], fill=bg_color)
        else:
            r = int(s * base_radius)
            draw.rounded_rectangle([(0, 0), (s - 1, s - 1)], radius=r, fill=bg_color)
        
    inset = s * 0.28
    arm = s * 0.18
    stroke = max(2, int(s * 0.055))
    r_cap = stroke // 2
    
    def draw_bracket(cx, cy, dx, dy):
        draw.ellipse([(cx - r_cap, cy - r_cap), (cx + r_cap, cy + r_cap)], fill=fg_color)
        hx = cx + dx * arm
        draw.line([(cx, cy), (hx, cy)], fill=fg_color, width=stroke)
        draw.ellipse([(hx - r_cap, cy - r_cap), (hx + r_cap, cy + r_cap)], fill=fg_color)
        vy = cy + dy * arm
        draw.line([(cx, cy), (cx, vy)], fill=fg_color, width=stroke)
        draw.ellipse([(cx - r_cap, vy - r_cap), (cx + r_cap, vy + r_cap)], fill=fg_color)

    draw_bracket(inset, inset, +1, +1)
    draw_bracket(s - inset, inset, -1, +1)
    draw_bracket(inset, s - inset, +1, -1)
    draw_bracket(s - inset, s - inset, -1, -1)
    
    center = s / 2.0
    dot_r = s * 0.038
    draw.ellipse([(center - dot_r, center - dot_r), (center + dot_r, center + dot_r)], fill=fg_color)
    
    return img.resize((size, size), Image.Resampling.LANCZOS)

# 1. Generate macOS iconset
print("🍏 Generating macOS AppIcon.icns...")
iconset_dir = "Acuity.iconset"
os.makedirs(iconset_dir, exist_ok=True)

mac_sizes = [
    ("icon_16x16.png", 16),
    ("icon_16x16@2x.png", 32),
    ("icon_32x32.png", 32),
    ("icon_32x32@2x.png", 64),
    ("icon_128x128.png", 128),
    ("icon_128x128@2x.png", 256),
    ("icon_256x256.png", 256),
    ("icon_256x256@2x.png", 512),
    ("icon_512x512.png", 512),
    ("icon_512x512@2x.png", 1024),
]

for name, sz in mac_sizes:
    img = draw_focus_frame(sz)
    img.save(os.path.join(iconset_dir, name))

# Run iconutil to create .icns
subprocess.run(["iconutil", "-c", "icns", iconset_dir, "-o", "mac-agent/Resources/AppIcon.icns"], check=True)
print("✅ Saved mac-agent/Resources/AppIcon.icns")

# 2. Generate macOS Menu Bar monochrome template icons
print("🍏 Generating MenuBar status item template icons...")
mb1x = draw_focus_frame(18, bg_color=None, fg_color=(255, 255, 255, 255))
mb2x = draw_focus_frame(36, bg_color=None, fg_color=(255, 255, 255, 255))
mb1x.save("mac-agent/Resources/MenuBarIcon.png")
mb2x.save("mac-agent/Resources/MenuBarIcon@2x.png")
print("✅ Saved MenuBarIcon.png and MenuBarIcon@2x.png")

# 3. Generate Android Mipmap Icons
print("🤖 Generating Android mipmap icons & Play Store 512x512...")
android_res = "android-client/app/src/main/res"

mipmap_densities = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

for folder, sz in mipmap_densities.items():
    folder_path = os.path.join(android_res, folder)
    os.makedirs(folder_path, exist_ok=True)
    # Square / Rounded
    sq = draw_focus_frame(sz, is_round=False)
    sq.save(os.path.join(folder_path, "ic_launcher.png"))
    # Round
    rd = draw_focus_frame(sz, is_round=True)
    rd.save(os.path.join(folder_path, "ic_launcher_round.png"))

# Play Store 512x512
playstore = draw_focus_frame(512, is_round=False)
playstore.save("android-client/app/src/main/ic_launcher-playstore.png")
playstore.save("android-client/app/src/main/res/drawable/ic_launcher_playstore.png")
print("✅ Saved Android mipmap icons and Play Store icon")

# Clean up temporary iconset
import shutil
shutil.rmtree(iconset_dir, ignore_errors=True)
print("🎉 All icons generated successfully!")
