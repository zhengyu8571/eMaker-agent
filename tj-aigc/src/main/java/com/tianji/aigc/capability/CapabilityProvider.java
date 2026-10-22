package com.tianji.aigc.capability;

import java.util.List;

/**
 * 批量提供由同一类项目对象适配出的Capability。
 */
public interface CapabilityProvider {

    List<Capability> capabilities();
}
