// author: kodeholic (powered by Claude)
//! SDP validator — 생성된 SDP의 기본 구조를 검증한다.
//! 프로덕션이 아닌 디버깅/테스트용.

/// SDP 검증 결과
#[derive(Debug)]
pub struct SdpValidation {
    pub valid: bool,
    pub errors: Vec<String>,
}

/// 생성된 SDP의 기본 구조를 검증한다.
pub fn validate_sdp(sdp: &str) -> SdpValidation {
    let mut errors = Vec::new();

    // v=0 헤더
    if !sdp.starts_with("v=0\r\n") {
        errors.push("missing v=0 header".to_string());
    }

    // BUNDLE 그룹
    if !sdp.contains("a=group:BUNDLE") {
        errors.push("missing BUNDLE group".to_string());
    }

    // ice-lite
    if !sdp.contains("a=ice-lite") {
        errors.push("missing ice-lite".to_string());
    }

    // m= 라인 개수
    let m_line_count = sdp.lines().filter(|l| l.starts_with("m=")).count();
    if m_line_count == 0 {
        errors.push("no m= lines".to_string());
    }

    // BUNDLE mids ↔ a=mid 일치 확인
    if let Some(bundle_line) = sdp.lines().find(|l| l.starts_with("a=group:BUNDLE ")) {
        let bundle_part = bundle_line
            .trim_start_matches("a=group:BUNDLE ")
            .trim();
        let bundle_mids: Vec<&str> = bundle_part.split_whitespace().collect();

        let actual_mids: Vec<&str> = sdp
            .lines()
            .filter_map(|l| l.strip_prefix("a=mid:"))
            .map(|m| m.trim())
            .collect();

        // BUNDLE에 있는 mid가 실제 m-line에 존재하는지만 검증
        // (inactive mid는 BUNDLE에 없어도 정상)
        for mid in &bundle_mids {
            if !actual_mids.contains(mid) {
                errors.push(format!(
                    "BUNDLE references mid={} but not found in sections",
                    mid
                ));
            }
        }
    }

    // 각 m= 섹션 검증 — \r\nm= 로 split
    let raw_sections: Vec<&str> = sdp.split("\r\nm=").collect();
    // 첫 번째는 session header, skip
    for (i, sec) in raw_sections.iter().skip(1).enumerate() {
        if !sec.contains("a=ice-ufrag:") {
            errors.push(format!("section {}: missing ice-ufrag", i));
        }
        if !sec.contains("a=ice-pwd:") {
            errors.push(format!("section {}: missing ice-pwd", i));
        }
        if !sec.contains("a=fingerprint:") {
            errors.push(format!("section {}: missing fingerprint", i));
        }
        if !sec.contains("a=mid:") {
            errors.push(format!("section {}: missing mid", i));
        }

        let has_direction = ["a=sendonly", "a=recvonly", "a=sendrecv", "a=inactive"]
            .iter()
            .any(|d| sec.contains(d));
        if !has_direction {
            errors.push(format!("section {}: missing direction", i));
        }
    }

    SdpValidation {
        valid: errors.is_empty(),
        errors,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::sdp::builder::build_publish_remote_sdp;
    use crate::sdp::types::*;

    #[test]
    fn validate_publish_sdp_passes() {
        let config = ServerConfig {
            ice: IceConfig {
                publish_ufrag: "uf".to_string(),
                publish_pwd: "pw".to_string(),
                subscribe_ufrag: "su".to_string(),
                subscribe_pwd: "sp".to_string(),
                ip: "1.2.3.4".to_string(),
                port: 19740,
            },
            dtls: DtlsConfig {
                fingerprint: "sha-256 AA:BB".to_string(),
                setup: "passive".to_string(),
            },
            codecs: vec![CodecConfig {
                kind: MediaKind::Audio,
                name: "opus".to_string(),
                pt: 111,
                clockrate: 48000,
                channels: Some(2),
                rtx_pt: None,
                rtcp_fb: vec![],
                fmtp: None,
            }],
            extmap: vec![],
            max_bitrate_bps: None,
        };

        let sdp = build_publish_remote_sdp(&config);
        let result = validate_sdp(&sdp);
        assert!(result.valid, "errors: {:?}", result.errors);
    }

    #[test]
    fn validate_catches_bad_sdp() {
        let bad_sdp = "garbage data\r\n";
        let result = validate_sdp(bad_sdp);
        assert!(!result.valid);
        assert!(!result.errors.is_empty());
    }
}
