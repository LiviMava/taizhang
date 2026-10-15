package com.example.systemlog.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public final class Requests {
    private Requests() {}
    public static class BaseWrite {
        @NotBlank @Size(max=200) public String name;
        @Size(max=100) public String maintainerName;
        @NotNull @Pattern(regexp="enabled|disabled") public String status = "enabled";
    }
    public static class Contact {
        @Positive public Long id;
        @NotNull @Pattern(regexp="tc|partner") public String contactSide;
        @NotBlank @Size(max=100) public String contactName;
        @Min(0) public int sortNo;
    }
    public static class Interface {
        @Positive public Long id;
        @NotNull @Pattern(regexp="tc|non_tc") public String providerSide;
        @NotBlank @Size(max=200) public String interfaceName;
        @NotNull @Pattern(regexp="active|invalid") public String integrationStatus = "active";
        @Size(max=255) public String testNacosServiceName;
        @Size(max=255) public String prodNacosServiceName;
        @Size(max=2048) public String testUrl;
        @Size(max=2048) public String prodUrl;
        @Size(max=1_000_000) public String messageSample;
        @Size(max=2048) public String testLogPath;
        @Size(max=2048) public String prodLogPath;
        @Size(max=10000) public String remark;
        @Min(0) public int sortNo;
    }
    public static class Attachment {
        @Positive public Long id;
        @NotBlank @Size(max=255) public String fileName;
        @NotNull @Pattern(regexp="dimou|local") public String storageSource;
        @NotBlank @Size(max=1024) public String storageRef;
        @PositiveOrZero public Long fileSize;
        @Size(max=127) public String contentType;
        @Min(0) public int sortNo;
    }
    public static class LedgerWrite {
        @NotNull @Positive public Long tcSystemId;
        @NotNull @Positive public Long partnerSystemId;
        @NotEmpty @Size(max=100) public List<@NotNull @Positive Long> divisionIds = new ArrayList<>();
        @NotNull @Size(max=100) public List<@NotNull @Valid Contact> contacts = new ArrayList<>();
        @NotEmpty @Size(max=500) public List<@NotNull @Valid Interface> interfaces = new ArrayList<>();
        @NotNull @Size(max=100) public List<@NotNull @Valid Attachment> attachments = new ArrayList<>();
        @Size(max=45) public String testIp;
        @Size(max=45) public String prodIp;
        @NotNull @Pattern(regexp="active|invalid") public String integrationStatus = "active";
        @Size(max=10000) public String preferencesText;
        @Size(max=10000) public String handleText;
        @Size(max=10000) public String remark;
        @Size(max=100) public String maintainerName;
        @AssertTrue(message="事业部不可重复选择") @JsonIgnore
        public boolean isUniqueDivisions() {
            return divisionIds == null || new HashSet<>(divisionIds).size() == divisionIds.size();
        }
    }
    public static class LedgerUpdate extends LedgerWrite {
        @NotNull @Min(1) @Max(2147483646) public Integer version;
    }
}
