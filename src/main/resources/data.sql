INSERT OR IGNORE INTO config
    (config_key, config_value, config_type, category, description)
VALUES
    ('HOOK_URL', '', 'string', 'webhook', '企业微信Webhook地址'),
    ('BASE_URL', '', 'string', 'api', 'API基础URL地址'),
    ('API_KEY', '', 'string', 'api', 'API访问密钥'),
    ('MODEL', '', 'string', 'ai', 'AI模型名称'),
    ('BOT_IS_SEND', '0', 'boolean', 'bot', '是否启用消息发送功能');
