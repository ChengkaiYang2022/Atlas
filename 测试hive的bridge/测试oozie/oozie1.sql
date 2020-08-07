-- 通过hue从hdfs倒入
-- ods_companyinfo_ent_financing_information
-- ods 插入历史表
insert into test_atlas.companyinfo_ent_financing_information select * from test_atlas.ods_companyinfo_ent_financing_information
