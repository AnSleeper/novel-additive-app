const fs = require('fs');
const path = require('path');
const dir = path.join(__dirname, '_web', 'assets');
const files = fs.readdirSync(dir).filter((f) => f.endsWith('.js'));

const cmds = `cancel_pipeline cleanup_after_stage cleanup_current_stage create_ai_model create_conversation create_manual_project create_project create_prompt_template delete_ai_model delete_conversation delete_project delete_prompt_template export_chapter_summary export_manual_project export_prompt_template get_ai_models get_category_config get_chapter get_chapter_context get_chapter_original_content get_chapter_stage_status get_config get_manual_project_info get_project get_prompt_template get_txt_split_rules goto_stage import_prompt_template list_ai_models list_chapters list_conversations list_manual_chapters list_projects list_prompt_templates load_conversation_history open_path parse_book parse_project_book preview_txt_chapters regenerate_rewrite regenerate_summary restore_chapter_original retry_failed save_manual_content save_rewritten_content save_txt_split_rules send_manual_message_stream set_default_ai_model show_in_folder start_pipeline start_rewrite_stage test_ai_connection update_ai_model update_chapter update_chapter_stage_status update_project update_prompt_template`.split(/\s+/);

const out = [];
for (const cmd of cmds) {
  out.push('\n\n########## ' + cmd + ' ##########');
  let found = 0;
  for (const f of files) {
    const t = fs.readFileSync(path.join(dir, f), 'utf8');
    let idx = 0;
    while ((idx = t.indexOf('"' + cmd + '"', idx)) !== -1) {
      out.push('--- ' + f + ' @' + idx);
      out.push(t.slice(Math.max(0, idx - 120), Math.min(t.length, idx + 520)));
      idx += cmd.length;
      found++;
      if (found > 6) break;
    }
    if (found > 6) break;
  }
  if (found === 0) out.push('(not found)');
}
fs.writeFileSync(path.join(__dirname, '_calls_ctx.txt'), out.join('\n'), 'utf8');
console.log('done', out.length);
