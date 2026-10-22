package com.tianji.aigc.memory;

import com.tianji.aigc.memory.model.EnhancedMemory.Conversation;
import com.tianji.aigc.memory.model.EnhancedMemory.Profile;

/** 增强数据与原 ChatMemory 独立保存。 */
public interface EnhancedMemoryRepository {
    // 读取用户当前会话的增强快照
    Conversation findConversation(Long userId, String sessionId);

    // 读取用户跨会话偏好
    Profile findProfile(Long userId);

    // 追加或清除会话时检查旧版本
    boolean saveConversation(Long userId, String sessionId, String expectedVersion, Conversation next);

    // 删除用户偏好时检查旧版本
    boolean saveProfile(Long userId, String expectedVersion, Profile next);

    // 摘要与偏好一起条件提交，避免只落地一半
    boolean saveUpdate(Long userId, String sessionId, String expectedConversationVersion, String expectedProfileVersion, Conversation conversation, Profile profile);
}
