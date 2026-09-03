CREATE TABLE IF NOT EXISTS cookie (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    platform VARCHAR(50) NOT NULL,
    cookie_value TEXT NOT NULL,
    remark TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_cookie_platform ON cookie(platform);

CREATE TABLE IF NOT EXISTS config (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    config_key VARCHAR(100) NOT NULL UNIQUE,
    config_value TEXT,
    config_type VARCHAR(50) NOT NULL DEFAULT 'string',
    category VARCHAR(50) NOT NULL DEFAULT 'general',
    description TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS ai (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    introduce TEXT,
    prompt TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS boss_config (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    debugger INTEGER DEFAULT 0,
    wait_time INTEGER DEFAULT 10,
    keywords VARCHAR(500),
    city_code VARCHAR(200),
    industry VARCHAR(200),
    job_type VARCHAR(50),
    experience VARCHAR(50),
    degree VARCHAR(200),
    salary VARCHAR(50),
    scale VARCHAR(200),
    stage VARCHAR(200),
    say_hi TEXT,
    expected_salary_min INTEGER,
    expected_salary_max INTEGER,
    enable_ai INTEGER DEFAULT 1,
    send_img_resume INTEGER DEFAULT 0,
    filter_dead_hr INTEGER DEFAULT 1,
    dead_status VARCHAR(200),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_boss_config_created ON boss_config(created_at);
CREATE INDEX IF NOT EXISTS idx_boss_config_updated ON boss_config(updated_at);

CREATE TABLE IF NOT EXISTS boss_blacklist (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    type VARCHAR(20) NOT NULL,
    value VARCHAR(200) NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_blacklist_type_value ON boss_blacklist(type, value);

CREATE TABLE IF NOT EXISTS boss_option (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    type TEXT NOT NULL,
    name TEXT NOT NULL,
    code TEXT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    sort_order INTEGER
);

CREATE INDEX IF NOT EXISTS idx_boss_option_type ON boss_option(type);

CREATE TABLE IF NOT EXISTS boss_data (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    encrypt_id TEXT,
    encrypt_user_id TEXT,
    company_name TEXT,
    job_name TEXT,
    salary TEXT,
    location TEXT,
    experience TEXT,
    degree TEXT,
    hr_name TEXT,
    hr_position TEXT,
    hr_active_status TEXT,
    delivery_status TEXT,
    job_description TEXT,
    job_url TEXT,
    recruitment_status TEXT,
    company_address TEXT,
    industry TEXT,
    introduce TEXT,
    financing_stage TEXT,
    company_scale TEXT,
    created_at TEXT,
    updated_at TEXT
);

CREATE TABLE IF NOT EXISTS liepin_config (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    keywords VARCHAR(500),
    city VARCHAR(200),
    salary_code VARCHAR(200),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_liepin_config_created ON liepin_config(created_at);
CREATE INDEX IF NOT EXISTS idx_liepin_config_updated ON liepin_config(updated_at);

CREATE TABLE IF NOT EXISTS liepin_option (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    type TEXT NOT NULL,
    name TEXT NOT NULL,
    code TEXT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    sort_order INTEGER
);

CREATE INDEX IF NOT EXISTS idx_liepin_option_type ON liepin_option(type);

CREATE TABLE IF NOT EXISTS liepin_data (
    job_id BIGINT PRIMARY KEY,
    job_title VARCHAR(200),
    job_link VARCHAR(300),
    job_salary_text VARCHAR(100),
    job_area VARCHAR(100),
    job_edu_req VARCHAR(50),
    job_exp_req VARCHAR(50),
    job_publish_time VARCHAR(50),
    comp_id BIGINT,
    comp_name VARCHAR(200),
    comp_industry VARCHAR(100),
    comp_scale VARCHAR(50),
    hr_id VARCHAR(64),
    hr_name VARCHAR(50),
    hr_title VARCHAR(100),
    hr_im_id VARCHAR(64),
    create_time DATETIME,
    update_time DATETIME,
    delivered INTEGER DEFAULT 0
);

CREATE TABLE IF NOT EXISTS job51_config (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    keywords VARCHAR(500),
    job_area VARCHAR(200),
    salary VARCHAR(200),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_job51_config_created ON job51_config(created_at);
CREATE INDEX IF NOT EXISTS idx_job51_config_updated ON job51_config(updated_at);

CREATE TABLE IF NOT EXISTS job51_option (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    type VARCHAR(50),
    name VARCHAR(100),
    code VARCHAR(100),
    sort_order INTEGER,
    created_at DATETIME,
    updated_at DATETIME
);

CREATE TABLE IF NOT EXISTS job51_data (
    job_id BIGINT PRIMARY KEY,
    job_title VARCHAR(200),
    job_link VARCHAR(300),
    job_salary_text VARCHAR(100),
    job_area VARCHAR(100),
    job_edu_req VARCHAR(50),
    job_exp_req VARCHAR(50),
    job_publish_time VARCHAR(50),
    comp_id BIGINT,
    comp_name VARCHAR(200),
    comp_industry VARCHAR(100),
    comp_scale VARCHAR(50),
    hr_id VARCHAR(64),
    hr_name VARCHAR(50),
    hr_title VARCHAR(100),
    delivered INTEGER DEFAULT 0,
    create_time DATETIME,
    update_time DATETIME
);

CREATE TABLE IF NOT EXISTS zhilian_config (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    keywords VARCHAR(500),
    city_code VARCHAR(200),
    salary VARCHAR(50),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_zhilian_config_created ON zhilian_config(created_at);
CREATE INDEX IF NOT EXISTS idx_zhilian_config_updated ON zhilian_config(updated_at);

CREATE TABLE IF NOT EXISTS zhilian_option (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    type VARCHAR(50),
    name VARCHAR(100),
    code VARCHAR(100),
    sort_order INTEGER,
    created_at DATETIME,
    updated_at DATETIME
);

CREATE TABLE IF NOT EXISTS zhilian_data (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    job_id VARCHAR(64),
    job_title VARCHAR(200),
    job_link VARCHAR(300),
    salary VARCHAR(100),
    location VARCHAR(100),
    experience VARCHAR(100),
    degree VARCHAR(100),
    company_name VARCHAR(200),
    delivery_status VARCHAR(20) DEFAULT '未投递',
    create_time DATETIME,
    update_time DATETIME
);

CREATE TABLE IF NOT EXISTS lagou_config (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    keywords VARCHAR(500),
    city VARCHAR(100),
    resume_type VARCHAR(20) DEFAULT 'ONLINE',
    resume_name VARCHAR(200),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_lagou_config_created ON lagou_config(created_at);
CREATE INDEX IF NOT EXISTS idx_lagou_config_updated ON lagou_config(updated_at);

CREATE TABLE IF NOT EXISTS lagou_option (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    type VARCHAR(50) NOT NULL,
    name VARCHAR(100) NOT NULL,
    code VARCHAR(100) NOT NULL,
    sort_order INTEGER,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_lagou_option_type_code ON lagou_option(type, code);

CREATE TABLE IF NOT EXISTS lagou_data (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    job_id VARCHAR(64) NOT NULL UNIQUE,
    job_title VARCHAR(200),
    job_link VARCHAR(300),
    salary VARCHAR(100),
    location VARCHAR(100),
    experience VARCHAR(100),
    degree VARCHAR(100),
    company_name VARCHAR(200),
    industry VARCHAR(100),
    company_scale VARCHAR(100),
    delivery_status VARCHAR(20) DEFAULT '未投递',
    create_time DATETIME,
    update_time DATETIME
);
