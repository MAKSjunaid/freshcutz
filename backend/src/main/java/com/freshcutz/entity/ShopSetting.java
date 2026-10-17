package com.freshcutz.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One small shop-wide setting stored as name -> value (for now: "default_category", the tab the customer
 * website opens on). A table of its own, so it is never overwritten by the Customize tab or its templates.
 * Created automatically by ddl-auto=update. */
@Entity
@Table(name = "shop_settings")
@Getter
@Setter
@NoArgsConstructor
public class ShopSetting {

    @Id
    @Column(name = "setting_name", length = 64)
    private String name;

    @Column(name = "setting_value", length = 200)
    private String value;

    public ShopSetting(String name, String value) {
        this.name = name;
        this.value = value;
    }
}
