package com.freshcutz.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** One saved design ("Template 1".."Template 4") of the customer website, kept
 * as the same settings JSON the live site uses. Exactly one template is the
 * primary one — its settings are copied onto the live website whenever it is
 * saved or made primary. The logo is shared by all templates. */
@Entity
@Table(name = "site_template")
@Getter
@Setter
public class SiteTemplate {

    /** 1..4 */
    @Id
    private Integer slot;

    @Column(name = "name", length = 60)
    private String name;

    @Column(name = "config_json", columnDefinition = "text")
    private String configJson;

    @Column(name = "is_primary")
    private boolean primary;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
