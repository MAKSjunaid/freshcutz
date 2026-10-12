package com.freshcutz.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** Single-row table (always id=1) holding the customer website's look as
 * edited in the admin's Customize tab: the settings as JSON text, plus the
 * custom logo bytes. The logo lives in the database rather than on disk for
 * the same reason product photos do — a free host's disk doesn't survive a
 * redeploy. */
@Entity
@Table(name = "site_customization")
@Getter
@Setter
public class SiteCustomization {

    @Id
    private Long id;

    @Column(name = "config_json", columnDefinition = "text")
    private String configJson;

    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(name = "logo_data", length = 2 * 1024 * 1024)
    private byte[] logoData;

    @Column(name = "logo_content_type", length = 100)
    private String logoContentType;

    /** When the logo last changed — becomes the ?v= cache-buster. */
    @Column(name = "logo_updated_at")
    private Instant logoUpdatedAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
