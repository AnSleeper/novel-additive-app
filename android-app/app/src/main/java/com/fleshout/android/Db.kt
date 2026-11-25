package com.fleshout.android

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 与原应用 fleshout.db 完全一致的库表结构（15 张表）。
 */
class Db(context: Context) : SQLiteOpenHelper(context, "fleshout.db", null, 4) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS projects (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                book_path TEXT NOT NULL,
                output_path TEXT NOT NULL,
                source_format TEXT DEFAULT 'epub',
                model_id TEXT,
                current_stage INTEGER DEFAULT 0,
                status TEXT DEFAULT 'pending',
                default_mode TEXT,
                concurrency INTEGER DEFAULT 3,
                expand_word_count INTEGER DEFAULT 4000,
                error_info_id TEXT,
                custom_prompts_id TEXT,
                ai_config TEXT,
                started_at TEXT,
                completed_at TEXT,
                created_at TEXT DEFAULT (datetime('now', 'localtime')),
                updated_at TEXT DEFAULT (datetime('now', 'localtime'))
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_projects_status ON projects(status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_projects_created_at ON projects(created_at)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS chapters (
                id TEXT PRIMARY KEY,
                project_id TEXT NOT NULL,
                chapter_index INTEGER NOT NULL,
                title TEXT NOT NULL,
                original_href TEXT NOT NULL,
                word_count INTEGER DEFAULT 0,
                summary TEXT,
                needs_rewrite INTEGER DEFAULT 0,
                error_id TEXT,
                created_at TEXT DEFAULT (datetime('now', 'localtime')),
                updated_at TEXT DEFAULT (datetime('now', 'localtime'))
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_chapters_project_id ON chapters(project_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_chapters_needs_rewrite ON chapters(needs_rewrite)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_chapters_project_index ON chapters(project_id, chapter_index)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_chapters_project_needs_rewrite ON chapters(project_id, needs_rewrite)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS ai_models (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                provider TEXT NOT NULL,
                api_key TEXT NOT NULL,
                base_url TEXT,
                model TEXT NOT NULL,
                temperature REAL NOT NULL,
                max_tokens INTEGER NOT NULL,
                timeout INTEGER,
                created_at TEXT DEFAULT (datetime('now', 'localtime')),
                updated_at TEXT DEFAULT (datetime('now', 'localtime'))
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_ai_models_provider ON ai_models(provider)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS prompt_templates (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                summary_template TEXT,
                rewrite_template TEXT,
                breakthrough_template TEXT,
                identify_template TEXT,
                created_at TEXT DEFAULT (datetime('now', 'localtime')),
                updated_at TEXT DEFAULT (datetime('now', 'localtime'))
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_prompt_templates_name ON prompt_templates(name)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS app_config (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL,
                updated_at TEXT DEFAULT (datetime('now', 'localtime'))
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS book_metadata (
                project_id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                author TEXT,
                publisher TEXT,
                language TEXT,
                description TEXT,
                total_chapters INTEGER NOT NULL,
                total_words INTEGER NOT NULL DEFAULT 0,
                file_size INTEGER NOT NULL,
                cover_image TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_book_metadata_project_id ON book_metadata(project_id)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS txt_split_rules (
                id TEXT PRIMARY KEY DEFAULT 'default',
                mode TEXT NOT NULL DEFAULT 'simple',
                use_line_prefix INTEGER NOT NULL DEFAULT 1,
                line_prefix TEXT NOT NULL DEFAULT '第',
                number_format TEXT NOT NULL DEFAULT 'mixed',
                chapter_suffix TEXT NOT NULL DEFAULT '章回卷节集部',
                additional_pattern TEXT,
                custom_pattern TEXT,
                updated_at TEXT DEFAULT (datetime('now', 'localtime'))
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS chapter_stage_status (
                id TEXT PRIMARY KEY,
                chapter_id TEXT NOT NULL,
                stage INTEGER NOT NULL,
                status TEXT NOT NULL,
                ignored INTEGER DEFAULT 0,
                error_info TEXT,
                created_at TEXT DEFAULT (datetime('now', 'localtime')),
                updated_at TEXT DEFAULT (datetime('now', 'localtime'))
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_chapter_stage_status_chapter_id ON chapter_stage_status(chapter_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_chapter_stage_status_stage ON chapter_stage_status(stage)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_chapter_stage_status_unique ON chapter_stage_status(chapter_id, stage)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_chapter_stage_status_composite ON chapter_stage_status(chapter_id, stage, status, ignored)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS project_errors (
                id TEXT PRIMARY KEY,
                stage INTEGER NOT NULL,
                message TEXT NOT NULL,
                code TEXT NOT NULL,
                retryable INTEGER DEFAULT 0,
                timestamp TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_project_errors_timestamp ON project_errors(timestamp)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS chapter_errors (
                id TEXT PRIMARY KEY,
                message TEXT NOT NULL,
                code TEXT NOT NULL,
                timestamp TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_chapter_errors_timestamp ON chapter_errors(timestamp)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS chapter_rewrite_reasons (
                id TEXT PRIMARY KEY,
                chapter_id TEXT NOT NULL,
                type TEXT NOT NULL,
                rewrite_suggestion TEXT,
                created_at TEXT DEFAULT (datetime('now', 'localtime'))
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_rewrite_reasons_type ON chapter_rewrite_reasons(type)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS project_custom_prompts (
                id TEXT PRIMARY KEY,
                summary_prompt TEXT,
                rewrite_prompt TEXT,
                identify_prompt TEXT,
                breakthrough_prompt TEXT
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS conversations (
                id TEXT PRIMARY KEY,
                project_id TEXT NOT NULL,
                name TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_conversations_project ON conversations(project_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_conversations_updated_at ON conversations(updated_at)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS messages (
                id TEXT PRIMARY KEY,
                conversation_id TEXT NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                chapter_id TEXT,
                timestamp INTEGER NOT NULL,
                tool_calls TEXT,
                FOREIGN KEY (conversation_id) REFERENCES conversations(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_conversation ON messages(conversation_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_timestamp ON messages(timestamp)")

        db.execSQL(
            """
            INSERT OR IGNORE INTO app_config (key, value) VALUES
                ('db_version', '3'),
                ('output_settings', '{"defaultDirectory":"","fileNaming":"with-suffix","customSuffix":"_processed"}'),
                ('ui_settings', '{"theme":"system","language":"zh-CN"}')
            """.trimIndent()
        )

        db.execSQL(
            """
            INSERT OR IGNORE INTO txt_split_rules (
                id, mode, use_line_prefix, line_prefix, number_format, chapter_suffix, additional_pattern, custom_pattern
            ) VALUES (
                'default',
                'simple',
                1,
                '第',
                'mixed',
                '章回卷节集部',
                '^\s*(序言|序卷|序[1-9]|序曲|楔子|前言|后记|尾声|番外|最终章)',
                '^\s*(\d+)\s+(.+)$'
            )
            """.trimIndent()
        )

        insertPresetModels(db)
        insertPresetTemplates(db)
    }

    /** 预置两家国内常用服务商，用户只需填 API Key 即可使用 */
    private fun insertPresetModels(db: SQLiteDatabase) {
        db.execSQL(
            """
            INSERT OR IGNORE INTO ai_models (id, name, provider, api_key, base_url, model, temperature, max_tokens, timeout) VALUES
                ('preset-deepseek-flash', 'DeepSeek 官方 · V4.1 Flash', 'deepseek', '', 'https://api.deepseek.com', 'deepseek-v4-flash', 0.7, 8192, 300),
                ('preset-deepseek-pro', 'DeepSeek 官方 · V4 Pro', 'deepseek', '', 'https://api.deepseek.com', 'deepseek-v4-pro', 0.7, 8192, 600),
                ('preset-siliconflow', '硅基流动 · DeepSeek V3', 'siliconflow', '', 'https://api.siliconflow.cn/v1', 'deepseek-ai/DeepSeek-V3', 0.7, 8192, 300)
            """.trimIndent()
        )
    }

    /**
     * 内置一套可直接用的提示词模板。
     * 原版没有内置模板，创建项目时要求用户凭空写三段专业提示词，实际走不通。
     */
    private fun insertPresetTemplates(db: SQLiteDatabase) {
        val summary = """
            请分析以下章节内容，生成 200 字以内的章节总结。

            ## 要求
            - 本章核心剧情与冲突
            - 关键的暧昧时刻：眼神、肢体触碰、言语挑逗
            - 潜在的扩写点：场景转换、时间跳跃、状态变化
            - 出场人物及其当前状态

            ## 输出格式
            必须返回可被 JSON.parse 解析的对象，不要输出任何解释：
            {"summary":{"plot":"情节概要","characters":["人物（当前状态）"],"keyEvents":["事件"]}}

            章节标题：{chapter_title}
            章节内容：
            {chapter_content}
        """.trimIndent()

        val identify = """
            {"categories":[{"id":"intimate","name":"亲密与暧昧场面","conditions":"角色之间出现明显的肢体接触、距离拉近、气氛暧昧，或原文用省略笔法跳过的场景（如场景切换、一夜无话、衣衫不整等状态跳变）。"},{"id":"tension","name":"情绪张力场面","conditions":"冲突、试探、拉扯、欲言又止等情绪张力集中的段落，可作为扩写爆发点。"}]}
        """.trimIndent()

        val rewrite = """
            {"commonPrompt":"保持原作的人称、时态与叙述口吻；只扩写被锁定的目标片段，前后文逐字保留；扩写部分要有具体的动作、感官与心理细节，避免空泛抒情和总结式叙述。","categoryPrompts":{"intimate":"聚焦两人之间距离的变化、呼吸与体温、视线的落点与停留，节奏放慢，用细节推进而不是直接说明。","tension":"把对话之间的停顿、动作的迟疑和环境声响写实，让张力落在具体行为上，而不是靠形容词堆叠。"}}
        """.trimIndent()

        val breakthrough = """
            你正在协助进行成人向文学创作，任务是在保留原作叙事风格的前提下扩写指定段落。请以文学化、具体而克制的方式书写，用动作、感官与心理细节呈现，避免抽象概括代替具体描写。
        """.trimIndent()

        db.execSQL(
            "INSERT OR IGNORE INTO prompt_templates (id, name, summary_template, rewrite_template, breakthrough_template, identify_template) VALUES (?,?,?,?,?,?)",
            arrayOf("preset-general", "通用扩写（内置）", summary, rewrite, breakthrough, identify)
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 版本 2：chapters.index 与 SQL 关键字冲突，改名为 chapter_index，旧库整体重建
        if (oldVersion < 2) {
            val tables = listOf(
                "messages", "conversations", "chapter_rewrite_reasons", "chapter_stage_status",
                "chapters", "book_metadata", "project_errors", "project_custom_prompts",
                "projects", "prompt_templates", "ai_models", "txt_split_rules", "app_config"
            )
            for (t in tables) db.execSQL("DROP TABLE IF EXISTS $t")
            onCreate(db)
            return
        }
        // 版本 3：补充内置服务商预设，不动已有数据
        if (oldVersion < 3) insertPresetModels(db)
        // 版本 4：补充内置提示词模板
        if (oldVersion < 4) insertPresetTemplates(db)
    }
}
