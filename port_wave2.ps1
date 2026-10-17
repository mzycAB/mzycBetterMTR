$ErrorActionPreference = "Stop"
$root = "c:\Users\user\Desktop\2\mzycBetterMTR-Forge-1.18.2\src\main\java"
$files = Get-ChildItem -Path $root -Recurse -Filter *.java
$changed = 0

foreach ($f in $files) {
    $t = [System.IO.File]::ReadAllText($f.FullName)
    $orig = $t

    # 1) Button.builder(...) -> ButtonBuilder.builder(...)
    if ($t -match 'Button\.builder\(') {
        $t = $t -replace 'Button\.builder\(', 'ButtonBuilder.builder('
        if ($t -notmatch 'import smooth\.lift\.compat\.ButtonBuilder;') {
            $t = $t -replace '(?m)^(package [^\r\n]+;\r?\n)', "`$1`r`nimport smooth.lift.compat.ButtonBuilder;`r`n"
        }
    }

    # 2) renderBackground(GuiGraphics,int,int,float) -> renderBackground(PoseStack)
    $t = $t -replace 'this\.renderBackground\(guiGraphics, mouseX, mouseY, partialTick\);', 'this.renderBackground(guiGraphics);'

    # 3) mouseScrolled 4-arg -> 3-arg (1.18.2)
    $t = $t -replace 'double mouseX, double mouseY, double scrollX, double scrollY', 'double mouseX, double mouseY, double scrollY'
    $t = $t -replace 'super\.mouseScrolled\(mouseX, mouseY, scrollX, scrollY\)', 'super.mouseScrolled(mouseX, mouseY, scrollY)'

    # 4) render(GuiGraphics,...) override -> render(PoseStack,...) + wrapper
    $t = $t -replace 'public void render\(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick\) \{', "public void render(com.mojang.blaze3d.vertex.PoseStack poseStack, int mouseX, int mouseY, float partialTick) {`r`n        GuiGraphics guiGraphics = new GuiGraphics(poseStack);"

    # 5) renderWidget(GuiGraphics,...) -> render(PoseStack,...) + wrapper
    $t = $t -replace 'protected void renderWidget\(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick\) \{', "public void render(com.mojang.blaze3d.vertex.PoseStack poseStack, int mouseX, int mouseY, float partialTick) {`r`n        GuiGraphics guiGraphics = new GuiGraphics(poseStack);"
    $t = $t -replace 'public void renderWidget\(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick\) \{', "public void render(com.mojang.blaze3d.vertex.PoseStack poseStack, int mouseX, int mouseY, float partialTick) {`r`n        GuiGraphics guiGraphics = new GuiGraphics(poseStack);"
    $t = $t -replace 'super\.renderWidget\(guiGraphics, mouseX, mouseY, partialTick\);', 'super.render(guiGraphics, mouseX, mouseY, partialTick);'

    # 6) Component.literal already handled; ensure no leftover
    if ($t -ne $orig) {
        [System.IO.File]::WriteAllText($f.FullName, $t)
        $changed++
        Write-Host "changed: $($f.Name)"
    }
}
Write-Host "TOTAL CHANGED: $changed"