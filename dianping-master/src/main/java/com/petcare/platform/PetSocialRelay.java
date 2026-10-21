package com.petcare.platform;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.*;

@Component @Profile("platform")
@ConditionalOnProperty(name="pet.social-relay-enabled",havingValue="true",matchIfMissing=true)
public class PetSocialRelay {
    private static final Logger LOG=LoggerFactory.getLogger(PetSocialRelay.class);
    private final PetSocialService social;
    public PetSocialRelay(PetSocialService social) { this.social=social; }
    @Scheduled(fixedDelay=2000,initialDelay=5000)
    public void relay() {
        try { social.deliverPosts(); }
        catch(RuntimeException error) { LOG.warn("动态推送暂未完成，数据库任务保留，下次重试",error); }
    }
}
