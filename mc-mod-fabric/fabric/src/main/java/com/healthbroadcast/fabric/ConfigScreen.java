package com.healthbroadcast.fabric;

import com.healthbroadcast.common.HealthBroadcaster;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/**
 * Fabric 配置界面 - 允许玩家在游戏中修改 UDP 端口
 */
public class ConfigScreen extends Screen {
    
    private final Screen parent;
    private TextFieldWidget portField;
    private String statusMessage = "";
    private int statusColor = 0xFFFFFF;
    
    public ConfigScreen(Screen parent) {
        super(Text.literal("Health Broadcast Settings"));
        this.parent = parent;
    }
    
    @Override
    protected void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        
        // 端口输入框
        portField = new TextFieldWidget(
            this.textRenderer,
            centerX - 50,
            centerY - 20,
            100,
            20,
            Text.literal("UDP Port")
        );
        portField.setText(String.valueOf(ModConfig.getInstance().udpPort));
        portField.setMaxLength(5);
        this.addDrawableChild(portField);
        
        // 保存按钮
        this.addDrawableChild(ButtonWidget.builder(
            Text.literal("Save"),
            button -> saveConfig()
        ).dimensions(centerX - 100, centerY + 20, 95, 20).build());
        
        // 返回按钮
        this.addDrawableChild(ButtonWidget.builder(
            Text.literal("Cancel"),
            button -> close()
        ).dimensions(centerX + 5, centerY + 20, 95, 20).build());
        
        // 重置按钮
        this.addDrawableChild(ButtonWidget.builder(
            Text.literal("Reset (" + HealthBroadcaster.DEFAULT_PORT + ")"),
            button -> {
                portField.setText(String.valueOf(HealthBroadcaster.DEFAULT_PORT));
                statusMessage = "Reset to default";
                statusColor = 0xFFFF00;
            }
        ).dimensions(centerX - 100, centerY + 50, 200, 20).build());
    }
    
    private void saveConfig() {
        try {
            int port = Integer.parseInt(portField.getText().trim());
            
            if (port < 1 || port > 65535) {
                statusMessage = "Port must be 1-65535";
                statusColor = 0xFF5555;
                return;
            }
            
            // 更新配置
            ModConfig config = ModConfig.getInstance();
            config.udpPort = port;
            config.save();
            config.apply();
            
            statusMessage = "Saved! Port: " + port;
            statusColor = 0x55FF55;
            
        } catch (NumberFormatException e) {
            statusMessage = "Invalid port number";
            statusColor = 0xFF5555;
        }
    }
    
    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context);
        
        // 标题
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 20, 0xFFFFFF);
        
        // 端口标签
        context.drawTextWithShadow(this.textRenderer, "UDP Port:", this.width / 2 - 100, this.height / 2 - 15, 0xAAAAAA);
        
        // 状态消息
        if (!statusMessage.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, statusMessage, this.width / 2, this.height / 2 + 80, statusColor);
        }
        
        // 说明
        context.drawCenteredTextWithShadow(this.textRenderer, "Data sent to 127.0.0.1:<port>", this.width / 2, this.height / 2 - 50, 0x888888);
        
        super.render(context, mouseX, mouseY, delta);
    }
    
    @Override
    public void close() {
        MinecraftClient.getInstance().setScreen(parent);
    }
}
