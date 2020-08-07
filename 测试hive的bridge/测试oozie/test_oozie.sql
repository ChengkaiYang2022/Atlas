truncate table ent_financing_information_daily;
-- 融资情况日报
insert into ent_financing_information_daily (round,round_count)
select round,count(1) from companyinfo_ent_financing_information where round != 'NULL' group by round


-- 查看融资情况
select * from ent_financing_information_daily order by round_count desc

CREATE TABLE `ent_financing_information_daily` (
  `round` string     COMMENT '融资轮次',
    `round_count` bigint   COMMENT '融资企业个数'
  )
comment '融资情况表——日报'
ROW FORMAT DELIMITED FIELDS TERMINATED BY ','
STORED AS parquet
;
