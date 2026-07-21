package org.example.pulse_ai.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "packages")
public class PackageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Short id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(name = "request_count", nullable = false)
    private int requestCount;

    @Column(name = "price_rub", nullable = false)
    private int priceRub;

    @Column(name = "stars_amount")
    private Integer starsAmount;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "sort_order", nullable = false)
    private short sortOrder;

    @Column(name = "perk_choices_count", nullable = false)
    private short perkChoicesCount;

    @Column(name = "includes_priority", nullable = false)
    private boolean includesPriority;
}
