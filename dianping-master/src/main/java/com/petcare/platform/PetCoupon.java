package com.petcare.platform;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data @TableName("pet_coupon")
public class PetCoupon {
    @TableId(type=IdType.AUTO) private Long id;
    private Long placeId;
    private String title;
    private Integer discountCents;
    private Integer minSpendCents;
    private Integer stock;
    private LocalDateTime startsAt;
    private LocalDateTime endsAt;
    private LocalDateTime validUntil;
    private Integer published;
}
