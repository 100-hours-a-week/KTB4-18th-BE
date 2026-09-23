-- Source: Ministry of the Interior and Safety administrative standard code system
-- URL: https://www.code.go.kr/stdcode/regCodeL.do
-- Snapshot effective date: 2026-09-17
-- Downloaded: 2026-09-23 (Asia/Seoul)
-- Source ZIP SHA-256: 44b96f4a86ad102057463a05aae8842f1d706d3e9e69d2dfc409023bf75ca56b
-- Active SIDO rows: 16, active SIGUNGU rows: 269

INSERT INTO `regions` (`code`, `name`, `level`, `parent_id`, `is_active`)
VALUES
    ('11', '서울특별시', 'SIDO', NULL, TRUE),
    ('12', '전남광주통합특별시', 'SIDO', NULL, TRUE),
    ('26', '부산광역시', 'SIDO', NULL, TRUE),
    ('27', '대구광역시', 'SIDO', NULL, TRUE),
    ('28', '인천광역시', 'SIDO', NULL, TRUE),
    ('30', '대전광역시', 'SIDO', NULL, TRUE),
    ('31', '울산광역시', 'SIDO', NULL, TRUE),
    ('36', '세종특별자치시', 'SIDO', NULL, TRUE),
    ('41', '경기도', 'SIDO', NULL, TRUE),
    ('43', '충청북도', 'SIDO', NULL, TRUE),
    ('44', '충청남도', 'SIDO', NULL, TRUE),
    ('47', '경상북도', 'SIDO', NULL, TRUE),
    ('48', '경상남도', 'SIDO', NULL, TRUE),
    ('50', '제주특별자치도', 'SIDO', NULL, TRUE),
    ('51', '강원특별자치도', 'SIDO', NULL, TRUE),
    ('52', '전북특별자치도', 'SIDO', NULL, TRUE)
ON DUPLICATE KEY UPDATE
    `name` = VALUES(`name`),
    `level` = VALUES(`level`),
    `parent_id` = VALUES(`parent_id`),
    `is_active` = VALUES(`is_active`),
    `updated_at` = CURRENT_TIMESTAMP;

INSERT INTO `regions` (`code`, `name`, `level`, `parent_id`, `is_active`)
SELECT
    seed.`code`,
    seed.`name`,
    'SIGUNGU',
    parent.`id`,
    TRUE
FROM (
    SELECT '11110' AS `code`, '종로구' AS `name`, '11' AS `parent_code`
    UNION ALL
    SELECT '11140' AS `code`, '중구' AS `name`, '11' AS `parent_code`
    UNION ALL
    SELECT '11170' AS `code`, '용산구' AS `name`, '11' AS `parent_code`
    UNION ALL
    SELECT '11200' AS `code`, '성동구' AS `name`, '11' AS `parent_code`
    UNION ALL
    SELECT '11215' AS `code`, '광진구' AS `name`, '11' AS `parent_code`
    UNION ALL
    SELECT '11230' AS `code`, '동대문구' AS `name`, '11' AS `parent_code`
    UNION ALL
    SELECT '11260' AS `code`, '중랑구' AS `name`, '11' AS `parent_code`
    UNION ALL
    SELECT '11290' AS `code`, '성북구' AS `name`, '11' AS `parent_code`
    UNION ALL
    SELECT '11305' AS `code`, '강북구' AS `name`, '11' AS `parent_code`
    UNION ALL
    SELECT '11320' AS `code`, '도봉구' AS `name`, '11' AS `parent_code`
    UNION ALL
    SELECT '11350' AS `code`, '노원구' AS `name`, '11' AS `parent_code`
    UNION ALL
    SELECT '11380' AS `code`, '은평구' AS `name`, '11' AS `parent_code`
    UNION ALL
    SELECT '11410' AS `code`, '서대문구' AS `name`, '11' AS `parent_code`
    UNION ALL
    SELECT '11440' AS `code`, '마포구' AS `name`, '11' AS `parent_code`
    UNION ALL
    SELECT '11470' AS `code`, '양천구' AS `name`, '11' AS `parent_code`
    UNION ALL
    SELECT '11500' AS `code`, '강서구' AS `name`, '11' AS `parent_code`
    UNION ALL
    SELECT '11530' AS `code`, '구로구' AS `name`, '11' AS `parent_code`
    UNION ALL
    SELECT '11545' AS `code`, '금천구' AS `name`, '11' AS `parent_code`
    UNION ALL
    SELECT '11560' AS `code`, '영등포구' AS `name`, '11' AS `parent_code`
    UNION ALL
    SELECT '11590' AS `code`, '동작구' AS `name`, '11' AS `parent_code`
    UNION ALL
    SELECT '11620' AS `code`, '관악구' AS `name`, '11' AS `parent_code`
    UNION ALL
    SELECT '11650' AS `code`, '서초구' AS `name`, '11' AS `parent_code`
    UNION ALL
    SELECT '11680' AS `code`, '강남구' AS `name`, '11' AS `parent_code`
    UNION ALL
    SELECT '11710' AS `code`, '송파구' AS `name`, '11' AS `parent_code`
    UNION ALL
    SELECT '11740' AS `code`, '강동구' AS `name`, '11' AS `parent_code`
    UNION ALL
    SELECT '12110' AS `code`, '목포시' AS `name`, '12' AS `parent_code`
    UNION ALL
    SELECT '12130' AS `code`, '여수시' AS `name`, '12' AS `parent_code`
    UNION ALL
    SELECT '12150' AS `code`, '순천시' AS `name`, '12' AS `parent_code`
    UNION ALL
    SELECT '12170' AS `code`, '나주시' AS `name`, '12' AS `parent_code`
    UNION ALL
    SELECT '12190' AS `code`, '광양시' AS `name`, '12' AS `parent_code`
    UNION ALL
    SELECT '12210' AS `code`, '동구' AS `name`, '12' AS `parent_code`
    UNION ALL
    SELECT '12240' AS `code`, '서구' AS `name`, '12' AS `parent_code`
    UNION ALL
    SELECT '12270' AS `code`, '남구' AS `name`, '12' AS `parent_code`
    UNION ALL
    SELECT '12300' AS `code`, '북구' AS `name`, '12' AS `parent_code`
    UNION ALL
    SELECT '12330' AS `code`, '광산구' AS `name`, '12' AS `parent_code`
    UNION ALL
    SELECT '12710' AS `code`, '담양군' AS `name`, '12' AS `parent_code`
    UNION ALL
    SELECT '12720' AS `code`, '곡성군' AS `name`, '12' AS `parent_code`
    UNION ALL
    SELECT '12730' AS `code`, '구례군' AS `name`, '12' AS `parent_code`
    UNION ALL
    SELECT '12740' AS `code`, '고흥군' AS `name`, '12' AS `parent_code`
    UNION ALL
    SELECT '12750' AS `code`, '보성군' AS `name`, '12' AS `parent_code`
    UNION ALL
    SELECT '12760' AS `code`, '화순군' AS `name`, '12' AS `parent_code`
    UNION ALL
    SELECT '12770' AS `code`, '장흥군' AS `name`, '12' AS `parent_code`
    UNION ALL
    SELECT '12780' AS `code`, '강진군' AS `name`, '12' AS `parent_code`
    UNION ALL
    SELECT '12790' AS `code`, '해남군' AS `name`, '12' AS `parent_code`
    UNION ALL
    SELECT '12800' AS `code`, '영암군' AS `name`, '12' AS `parent_code`
    UNION ALL
    SELECT '12810' AS `code`, '무안군' AS `name`, '12' AS `parent_code`
    UNION ALL
    SELECT '12820' AS `code`, '함평군' AS `name`, '12' AS `parent_code`
    UNION ALL
    SELECT '12830' AS `code`, '영광군' AS `name`, '12' AS `parent_code`
    UNION ALL
    SELECT '12840' AS `code`, '장성군' AS `name`, '12' AS `parent_code`
    UNION ALL
    SELECT '12850' AS `code`, '완도군' AS `name`, '12' AS `parent_code`
    UNION ALL
    SELECT '12860' AS `code`, '진도군' AS `name`, '12' AS `parent_code`
    UNION ALL
    SELECT '12870' AS `code`, '신안군' AS `name`, '12' AS `parent_code`
    UNION ALL
    SELECT '26110' AS `code`, '중구' AS `name`, '26' AS `parent_code`
    UNION ALL
    SELECT '26140' AS `code`, '서구' AS `name`, '26' AS `parent_code`
    UNION ALL
    SELECT '26170' AS `code`, '동구' AS `name`, '26' AS `parent_code`
    UNION ALL
    SELECT '26200' AS `code`, '영도구' AS `name`, '26' AS `parent_code`
    UNION ALL
    SELECT '26230' AS `code`, '부산진구' AS `name`, '26' AS `parent_code`
    UNION ALL
    SELECT '26260' AS `code`, '동래구' AS `name`, '26' AS `parent_code`
    UNION ALL
    SELECT '26290' AS `code`, '남구' AS `name`, '26' AS `parent_code`
    UNION ALL
    SELECT '26320' AS `code`, '북구' AS `name`, '26' AS `parent_code`
    UNION ALL
    SELECT '26350' AS `code`, '해운대구' AS `name`, '26' AS `parent_code`
    UNION ALL
    SELECT '26380' AS `code`, '사하구' AS `name`, '26' AS `parent_code`
    UNION ALL
    SELECT '26410' AS `code`, '금정구' AS `name`, '26' AS `parent_code`
    UNION ALL
    SELECT '26440' AS `code`, '강서구' AS `name`, '26' AS `parent_code`
    UNION ALL
    SELECT '26470' AS `code`, '연제구' AS `name`, '26' AS `parent_code`
    UNION ALL
    SELECT '26500' AS `code`, '수영구' AS `name`, '26' AS `parent_code`
    UNION ALL
    SELECT '26530' AS `code`, '사상구' AS `name`, '26' AS `parent_code`
    UNION ALL
    SELECT '26710' AS `code`, '기장군' AS `name`, '26' AS `parent_code`
    UNION ALL
    SELECT '27110' AS `code`, '중구' AS `name`, '27' AS `parent_code`
    UNION ALL
    SELECT '27140' AS `code`, '동구' AS `name`, '27' AS `parent_code`
    UNION ALL
    SELECT '27170' AS `code`, '서구' AS `name`, '27' AS `parent_code`
    UNION ALL
    SELECT '27200' AS `code`, '남구' AS `name`, '27' AS `parent_code`
    UNION ALL
    SELECT '27230' AS `code`, '북구' AS `name`, '27' AS `parent_code`
    UNION ALL
    SELECT '27260' AS `code`, '수성구' AS `name`, '27' AS `parent_code`
    UNION ALL
    SELECT '27290' AS `code`, '달서구' AS `name`, '27' AS `parent_code`
    UNION ALL
    SELECT '27710' AS `code`, '달성군' AS `name`, '27' AS `parent_code`
    UNION ALL
    SELECT '27720' AS `code`, '군위군' AS `name`, '27' AS `parent_code`
    UNION ALL
    SELECT '28125' AS `code`, '제물포구' AS `name`, '28' AS `parent_code`
    UNION ALL
    SELECT '28155' AS `code`, '영종구' AS `name`, '28' AS `parent_code`
    UNION ALL
    SELECT '28177' AS `code`, '미추홀구' AS `name`, '28' AS `parent_code`
    UNION ALL
    SELECT '28185' AS `code`, '연수구' AS `name`, '28' AS `parent_code`
    UNION ALL
    SELECT '28200' AS `code`, '남동구' AS `name`, '28' AS `parent_code`
    UNION ALL
    SELECT '28237' AS `code`, '부평구' AS `name`, '28' AS `parent_code`
    UNION ALL
    SELECT '28245' AS `code`, '계양구' AS `name`, '28' AS `parent_code`
    UNION ALL
    SELECT '28275' AS `code`, '서해구' AS `name`, '28' AS `parent_code`
    UNION ALL
    SELECT '28290' AS `code`, '검단구' AS `name`, '28' AS `parent_code`
    UNION ALL
    SELECT '28710' AS `code`, '강화군' AS `name`, '28' AS `parent_code`
    UNION ALL
    SELECT '28720' AS `code`, '옹진군' AS `name`, '28' AS `parent_code`
    UNION ALL
    SELECT '30110' AS `code`, '동구' AS `name`, '30' AS `parent_code`
    UNION ALL
    SELECT '30140' AS `code`, '중구' AS `name`, '30' AS `parent_code`
    UNION ALL
    SELECT '30170' AS `code`, '서구' AS `name`, '30' AS `parent_code`
    UNION ALL
    SELECT '30200' AS `code`, '유성구' AS `name`, '30' AS `parent_code`
    UNION ALL
    SELECT '30230' AS `code`, '대덕구' AS `name`, '30' AS `parent_code`
    UNION ALL
    SELECT '31110' AS `code`, '중구' AS `name`, '31' AS `parent_code`
    UNION ALL
    SELECT '31140' AS `code`, '남구' AS `name`, '31' AS `parent_code`
    UNION ALL
    SELECT '31170' AS `code`, '동구' AS `name`, '31' AS `parent_code`
    UNION ALL
    SELECT '31200' AS `code`, '북구' AS `name`, '31' AS `parent_code`
    UNION ALL
    SELECT '31710' AS `code`, '울주군' AS `name`, '31' AS `parent_code`
    UNION ALL
    SELECT '36110' AS `code`, '세종특별자치시' AS `name`, '36' AS `parent_code`
    UNION ALL
    SELECT '41110' AS `code`, '수원시' AS `name`, '41' AS `parent_code`
    UNION ALL
    SELECT '41111' AS `code`, '수원시 장안구' AS `name`, '41' AS `parent_code`
    UNION ALL
    SELECT '41113' AS `code`, '수원시 권선구' AS `name`, '41' AS `parent_code`
    UNION ALL
    SELECT '41115' AS `code`, '수원시 팔달구' AS `name`, '41' AS `parent_code`
    UNION ALL
    SELECT '41117' AS `code`, '수원시 영통구' AS `name`, '41' AS `parent_code`
    UNION ALL
    SELECT '41130' AS `code`, '성남시' AS `name`, '41' AS `parent_code`
    UNION ALL
    SELECT '41131' AS `code`, '성남시 수정구' AS `name`, '41' AS `parent_code`
    UNION ALL
    SELECT '41133' AS `code`, '성남시 중원구' AS `name`, '41' AS `parent_code`
    UNION ALL
    SELECT '41135' AS `code`, '성남시 분당구' AS `name`, '41' AS `parent_code`
    UNION ALL
    SELECT '41150' AS `code`, '의정부시' AS `name`, '41' AS `parent_code`
    UNION ALL
    SELECT '41170' AS `code`, '안양시' AS `name`, '41' AS `parent_code`
    UNION ALL
    SELECT '41171' AS `code`, '안양시 만안구' AS `name`, '41' AS `parent_code`
    UNION ALL
    SELECT '41173' AS `code`, '안양시 동안구' AS `name`, '41' AS `parent_code`
    UNION ALL
    SELECT '41190' AS `code`, '부천시' AS `name`, '41' AS `parent_code`
    UNION ALL
    SELECT '41192' AS `code`, '부천시 원미구' AS `name`, '41' AS `parent_code`
    UNION ALL
    SELECT '41194' AS `code`, '부천시 소사구' AS `name`, '41' AS `parent_code`
    UNION ALL
    SELECT '41196' AS `code`, '부천시 오정구' AS `name`, '41' AS `parent_code`
    UNION ALL
    SELECT '41210' AS `code`, '광명시' AS `name`, '41' AS `parent_code`
    UNION ALL
    SELECT '41220' AS `code`, '평택시' AS `name`, '41' AS `parent_code`
    UNION ALL
    SELECT '41250' AS `code`, '동두천시' AS `name`, '41' AS `parent_code`
    UNION ALL
    SELECT '41270' AS `code`, '안산시' AS `name`, '41' AS `parent_code`
    UNION ALL
    SELECT '41271' AS `code`, '안산시 상록구' AS `name`, '41' AS `parent_code`
    UNION ALL
    SELECT '41273' AS `code`, '안산시 단원구' AS `name`, '41' AS `parent_code`
    UNION ALL
    SELECT '41280' AS `code`, '고양시' AS `name`, '41' AS `parent_code`
    UNION ALL
    SELECT '41281' AS `code`, '고양시 덕양구' AS `name`, '41' AS `parent_code`
    UNION ALL
    SELECT '41285' AS `code`, '고양시 일산동구' AS `name`, '41' AS `parent_code`
    UNION ALL
    SELECT '41287' AS `code`, '고양시 일산서구' AS `name`, '41' AS `parent_code`
    UNION ALL
    SELECT '41290' AS `code`, '과천시' AS `name`, '41' AS `parent_code`
    UNION ALL
    SELECT '41310' AS `code`, '구리시' AS `name`, '41' AS `parent_code`
    UNION ALL
    SELECT '41360' AS `code`, '남양주시' AS `name`, '41' AS `parent_code`
    UNION ALL
    SELECT '41370' AS `code`, '오산시' AS `name`, '41' AS `parent_code`
    UNION ALL
    SELECT '41390' AS `code`, '시흥시' AS `name`, '41' AS `parent_code`
    UNION ALL
    SELECT '41410' AS `code`, '군포시' AS `name`, '41' AS `parent_code`
    UNION ALL
    SELECT '41430' AS `code`, '의왕시' AS `name`, '41' AS `parent_code`
    UNION ALL
    SELECT '41450' AS `code`, '하남시' AS `name`, '41' AS `parent_code`
    UNION ALL
    SELECT '41460' AS `code`, '용인시' AS `name`, '41' AS `parent_code`
    UNION ALL
    SELECT '41461' AS `code`, '용인시 처인구' AS `name`, '41' AS `parent_code`
    UNION ALL
    SELECT '41463' AS `code`, '용인시 기흥구' AS `name`, '41' AS `parent_code`
    UNION ALL
    SELECT '41465' AS `code`, '용인시 수지구' AS `name`, '41' AS `parent_code`
    UNION ALL
    SELECT '41480' AS `code`, '파주시' AS `name`, '41' AS `parent_code`
    UNION ALL
    SELECT '41500' AS `code`, '이천시' AS `name`, '41' AS `parent_code`
    UNION ALL
    SELECT '41550' AS `code`, '안성시' AS `name`, '41' AS `parent_code`
    UNION ALL
    SELECT '41570' AS `code`, '김포시' AS `name`, '41' AS `parent_code`
    UNION ALL
    SELECT '41590' AS `code`, '화성시' AS `name`, '41' AS `parent_code`
    UNION ALL
    SELECT '41591' AS `code`, '화성시 만세구' AS `name`, '41' AS `parent_code`
    UNION ALL
    SELECT '41593' AS `code`, '화성시 효행구' AS `name`, '41' AS `parent_code`
    UNION ALL
    SELECT '41595' AS `code`, '화성시 병점구' AS `name`, '41' AS `parent_code`
    UNION ALL
    SELECT '41597' AS `code`, '화성시 동탄구' AS `name`, '41' AS `parent_code`
    UNION ALL
    SELECT '41610' AS `code`, '광주시' AS `name`, '41' AS `parent_code`
    UNION ALL
    SELECT '41630' AS `code`, '양주시' AS `name`, '41' AS `parent_code`
    UNION ALL
    SELECT '41650' AS `code`, '포천시' AS `name`, '41' AS `parent_code`
    UNION ALL
    SELECT '41670' AS `code`, '여주시' AS `name`, '41' AS `parent_code`
    UNION ALL
    SELECT '41800' AS `code`, '연천군' AS `name`, '41' AS `parent_code`
    UNION ALL
    SELECT '41820' AS `code`, '가평군' AS `name`, '41' AS `parent_code`
    UNION ALL
    SELECT '41830' AS `code`, '양평군' AS `name`, '41' AS `parent_code`
    UNION ALL
    SELECT '43110' AS `code`, '청주시' AS `name`, '43' AS `parent_code`
    UNION ALL
    SELECT '43111' AS `code`, '청주시 상당구' AS `name`, '43' AS `parent_code`
    UNION ALL
    SELECT '43112' AS `code`, '청주시 서원구' AS `name`, '43' AS `parent_code`
    UNION ALL
    SELECT '43113' AS `code`, '청주시 흥덕구' AS `name`, '43' AS `parent_code`
    UNION ALL
    SELECT '43114' AS `code`, '청주시 청원구' AS `name`, '43' AS `parent_code`
    UNION ALL
    SELECT '43130' AS `code`, '충주시' AS `name`, '43' AS `parent_code`
    UNION ALL
    SELECT '43150' AS `code`, '제천시' AS `name`, '43' AS `parent_code`
    UNION ALL
    SELECT '43720' AS `code`, '보은군' AS `name`, '43' AS `parent_code`
    UNION ALL
    SELECT '43730' AS `code`, '옥천군' AS `name`, '43' AS `parent_code`
    UNION ALL
    SELECT '43740' AS `code`, '영동군' AS `name`, '43' AS `parent_code`
    UNION ALL
    SELECT '43745' AS `code`, '증평군' AS `name`, '43' AS `parent_code`
    UNION ALL
    SELECT '43750' AS `code`, '진천군' AS `name`, '43' AS `parent_code`
    UNION ALL
    SELECT '43760' AS `code`, '괴산군' AS `name`, '43' AS `parent_code`
    UNION ALL
    SELECT '43770' AS `code`, '음성군' AS `name`, '43' AS `parent_code`
    UNION ALL
    SELECT '43800' AS `code`, '단양군' AS `name`, '43' AS `parent_code`
    UNION ALL
    SELECT '44130' AS `code`, '천안시' AS `name`, '44' AS `parent_code`
    UNION ALL
    SELECT '44131' AS `code`, '천안시 동남구' AS `name`, '44' AS `parent_code`
    UNION ALL
    SELECT '44133' AS `code`, '천안시 서북구' AS `name`, '44' AS `parent_code`
    UNION ALL
    SELECT '44150' AS `code`, '공주시' AS `name`, '44' AS `parent_code`
    UNION ALL
    SELECT '44180' AS `code`, '보령시' AS `name`, '44' AS `parent_code`
    UNION ALL
    SELECT '44200' AS `code`, '아산시' AS `name`, '44' AS `parent_code`
    UNION ALL
    SELECT '44210' AS `code`, '서산시' AS `name`, '44' AS `parent_code`
    UNION ALL
    SELECT '44230' AS `code`, '논산시' AS `name`, '44' AS `parent_code`
    UNION ALL
    SELECT '44250' AS `code`, '계룡시' AS `name`, '44' AS `parent_code`
    UNION ALL
    SELECT '44270' AS `code`, '당진시' AS `name`, '44' AS `parent_code`
    UNION ALL
    SELECT '44710' AS `code`, '금산군' AS `name`, '44' AS `parent_code`
    UNION ALL
    SELECT '44760' AS `code`, '부여군' AS `name`, '44' AS `parent_code`
    UNION ALL
    SELECT '44770' AS `code`, '서천군' AS `name`, '44' AS `parent_code`
    UNION ALL
    SELECT '44790' AS `code`, '청양군' AS `name`, '44' AS `parent_code`
    UNION ALL
    SELECT '44800' AS `code`, '홍성군' AS `name`, '44' AS `parent_code`
    UNION ALL
    SELECT '44810' AS `code`, '예산군' AS `name`, '44' AS `parent_code`
    UNION ALL
    SELECT '44825' AS `code`, '태안군' AS `name`, '44' AS `parent_code`
    UNION ALL
    SELECT '47110' AS `code`, '포항시' AS `name`, '47' AS `parent_code`
    UNION ALL
    SELECT '47111' AS `code`, '포항시 남구' AS `name`, '47' AS `parent_code`
    UNION ALL
    SELECT '47113' AS `code`, '포항시 북구' AS `name`, '47' AS `parent_code`
    UNION ALL
    SELECT '47130' AS `code`, '경주시' AS `name`, '47' AS `parent_code`
    UNION ALL
    SELECT '47150' AS `code`, '김천시' AS `name`, '47' AS `parent_code`
    UNION ALL
    SELECT '47170' AS `code`, '안동시' AS `name`, '47' AS `parent_code`
    UNION ALL
    SELECT '47190' AS `code`, '구미시' AS `name`, '47' AS `parent_code`
    UNION ALL
    SELECT '47210' AS `code`, '영주시' AS `name`, '47' AS `parent_code`
    UNION ALL
    SELECT '47230' AS `code`, '영천시' AS `name`, '47' AS `parent_code`
    UNION ALL
    SELECT '47250' AS `code`, '상주시' AS `name`, '47' AS `parent_code`
    UNION ALL
    SELECT '47280' AS `code`, '문경시' AS `name`, '47' AS `parent_code`
    UNION ALL
    SELECT '47290' AS `code`, '경산시' AS `name`, '47' AS `parent_code`
    UNION ALL
    SELECT '47730' AS `code`, '의성군' AS `name`, '47' AS `parent_code`
    UNION ALL
    SELECT '47750' AS `code`, '청송군' AS `name`, '47' AS `parent_code`
    UNION ALL
    SELECT '47760' AS `code`, '영양군' AS `name`, '47' AS `parent_code`
    UNION ALL
    SELECT '47770' AS `code`, '영덕군' AS `name`, '47' AS `parent_code`
    UNION ALL
    SELECT '47820' AS `code`, '청도군' AS `name`, '47' AS `parent_code`
    UNION ALL
    SELECT '47830' AS `code`, '고령군' AS `name`, '47' AS `parent_code`
    UNION ALL
    SELECT '47840' AS `code`, '성주군' AS `name`, '47' AS `parent_code`
    UNION ALL
    SELECT '47850' AS `code`, '칠곡군' AS `name`, '47' AS `parent_code`
    UNION ALL
    SELECT '47900' AS `code`, '예천군' AS `name`, '47' AS `parent_code`
    UNION ALL
    SELECT '47920' AS `code`, '봉화군' AS `name`, '47' AS `parent_code`
    UNION ALL
    SELECT '47930' AS `code`, '울진군' AS `name`, '47' AS `parent_code`
    UNION ALL
    SELECT '47940' AS `code`, '울릉군' AS `name`, '47' AS `parent_code`
    UNION ALL
    SELECT '48120' AS `code`, '창원시' AS `name`, '48' AS `parent_code`
    UNION ALL
    SELECT '48121' AS `code`, '창원시 의창구' AS `name`, '48' AS `parent_code`
    UNION ALL
    SELECT '48123' AS `code`, '창원시 성산구' AS `name`, '48' AS `parent_code`
    UNION ALL
    SELECT '48125' AS `code`, '창원시 마산합포구' AS `name`, '48' AS `parent_code`
    UNION ALL
    SELECT '48127' AS `code`, '창원시 마산회원구' AS `name`, '48' AS `parent_code`
    UNION ALL
    SELECT '48129' AS `code`, '창원시 진해구' AS `name`, '48' AS `parent_code`
    UNION ALL
    SELECT '48170' AS `code`, '진주시' AS `name`, '48' AS `parent_code`
    UNION ALL
    SELECT '48220' AS `code`, '통영시' AS `name`, '48' AS `parent_code`
    UNION ALL
    SELECT '48240' AS `code`, '사천시' AS `name`, '48' AS `parent_code`
    UNION ALL
    SELECT '48250' AS `code`, '김해시' AS `name`, '48' AS `parent_code`
    UNION ALL
    SELECT '48270' AS `code`, '밀양시' AS `name`, '48' AS `parent_code`
    UNION ALL
    SELECT '48310' AS `code`, '거제시' AS `name`, '48' AS `parent_code`
    UNION ALL
    SELECT '48330' AS `code`, '양산시' AS `name`, '48' AS `parent_code`
    UNION ALL
    SELECT '48720' AS `code`, '의령군' AS `name`, '48' AS `parent_code`
    UNION ALL
    SELECT '48730' AS `code`, '함안군' AS `name`, '48' AS `parent_code`
    UNION ALL
    SELECT '48740' AS `code`, '창녕군' AS `name`, '48' AS `parent_code`
    UNION ALL
    SELECT '48820' AS `code`, '고성군' AS `name`, '48' AS `parent_code`
    UNION ALL
    SELECT '48840' AS `code`, '남해군' AS `name`, '48' AS `parent_code`
    UNION ALL
    SELECT '48850' AS `code`, '하동군' AS `name`, '48' AS `parent_code`
    UNION ALL
    SELECT '48860' AS `code`, '산청군' AS `name`, '48' AS `parent_code`
    UNION ALL
    SELECT '48870' AS `code`, '함양군' AS `name`, '48' AS `parent_code`
    UNION ALL
    SELECT '48880' AS `code`, '거창군' AS `name`, '48' AS `parent_code`
    UNION ALL
    SELECT '48890' AS `code`, '합천군' AS `name`, '48' AS `parent_code`
    UNION ALL
    SELECT '50110' AS `code`, '제주시' AS `name`, '50' AS `parent_code`
    UNION ALL
    SELECT '50130' AS `code`, '서귀포시' AS `name`, '50' AS `parent_code`
    UNION ALL
    SELECT '51110' AS `code`, '춘천시' AS `name`, '51' AS `parent_code`
    UNION ALL
    SELECT '51130' AS `code`, '원주시' AS `name`, '51' AS `parent_code`
    UNION ALL
    SELECT '51150' AS `code`, '강릉시' AS `name`, '51' AS `parent_code`
    UNION ALL
    SELECT '51170' AS `code`, '동해시' AS `name`, '51' AS `parent_code`
    UNION ALL
    SELECT '51190' AS `code`, '태백시' AS `name`, '51' AS `parent_code`
    UNION ALL
    SELECT '51210' AS `code`, '속초시' AS `name`, '51' AS `parent_code`
    UNION ALL
    SELECT '51230' AS `code`, '삼척시' AS `name`, '51' AS `parent_code`
    UNION ALL
    SELECT '51720' AS `code`, '홍천군' AS `name`, '51' AS `parent_code`
    UNION ALL
    SELECT '51730' AS `code`, '횡성군' AS `name`, '51' AS `parent_code`
    UNION ALL
    SELECT '51750' AS `code`, '영월군' AS `name`, '51' AS `parent_code`
    UNION ALL
    SELECT '51760' AS `code`, '평창군' AS `name`, '51' AS `parent_code`
    UNION ALL
    SELECT '51770' AS `code`, '정선군' AS `name`, '51' AS `parent_code`
    UNION ALL
    SELECT '51780' AS `code`, '철원군' AS `name`, '51' AS `parent_code`
    UNION ALL
    SELECT '51790' AS `code`, '화천군' AS `name`, '51' AS `parent_code`
    UNION ALL
    SELECT '51800' AS `code`, '양구군' AS `name`, '51' AS `parent_code`
    UNION ALL
    SELECT '51810' AS `code`, '인제군' AS `name`, '51' AS `parent_code`
    UNION ALL
    SELECT '51820' AS `code`, '고성군' AS `name`, '51' AS `parent_code`
    UNION ALL
    SELECT '51830' AS `code`, '양양군' AS `name`, '51' AS `parent_code`
    UNION ALL
    SELECT '52110' AS `code`, '전주시' AS `name`, '52' AS `parent_code`
    UNION ALL
    SELECT '52111' AS `code`, '전주시 완산구' AS `name`, '52' AS `parent_code`
    UNION ALL
    SELECT '52113' AS `code`, '전주시 덕진구' AS `name`, '52' AS `parent_code`
    UNION ALL
    SELECT '52130' AS `code`, '군산시' AS `name`, '52' AS `parent_code`
    UNION ALL
    SELECT '52140' AS `code`, '익산시' AS `name`, '52' AS `parent_code`
    UNION ALL
    SELECT '52180' AS `code`, '정읍시' AS `name`, '52' AS `parent_code`
    UNION ALL
    SELECT '52190' AS `code`, '남원시' AS `name`, '52' AS `parent_code`
    UNION ALL
    SELECT '52210' AS `code`, '김제시' AS `name`, '52' AS `parent_code`
    UNION ALL
    SELECT '52710' AS `code`, '완주군' AS `name`, '52' AS `parent_code`
    UNION ALL
    SELECT '52720' AS `code`, '진안군' AS `name`, '52' AS `parent_code`
    UNION ALL
    SELECT '52730' AS `code`, '무주군' AS `name`, '52' AS `parent_code`
    UNION ALL
    SELECT '52740' AS `code`, '장수군' AS `name`, '52' AS `parent_code`
    UNION ALL
    SELECT '52750' AS `code`, '임실군' AS `name`, '52' AS `parent_code`
    UNION ALL
    SELECT '52770' AS `code`, '순창군' AS `name`, '52' AS `parent_code`
    UNION ALL
    SELECT '52790' AS `code`, '고창군' AS `name`, '52' AS `parent_code`
    UNION ALL
    SELECT '52800' AS `code`, '부안군' AS `name`, '52' AS `parent_code`
) AS seed
JOIN `regions` AS parent
    ON parent.`code` = seed.`parent_code`
    AND parent.`level` = 'SIDO'
ON DUPLICATE KEY UPDATE
    `name` = VALUES(`name`),
    `level` = VALUES(`level`),
    `parent_id` = VALUES(`parent_id`),
    `is_active` = VALUES(`is_active`),
    `updated_at` = CURRENT_TIMESTAMP;

INSERT IGNORE INTO `chat_rooms` (`region_id`, `capacity`, `status`)
SELECT
    region.`id`,
    25,
    'ACTIVE'
FROM `regions` AS region
WHERE region.`level` = 'SIGUNGU'
    AND region.`is_active` = TRUE;
