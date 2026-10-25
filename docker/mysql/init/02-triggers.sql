-- 由上一轮 PowerShell 虚拟环境方案从原始 sql.sql 自动改写的 7 个触发器（DEFINER=root，DELIMITER $$ 包裹）
USE bilibili;
DELIMITER $$
CREATE DEFINER=`root`@`%` TRIGGER `increment_play_count` AFTER INSERT ON `play` FOR EACH ROW BEGIN
    UPDATE video_data
    SET play_count = play_count  + 1
    WHERE video_id = NEW.video_id;
END$$
CREATE DEFINER=`root`@`%` TRIGGER `increment_comment_count` AFTER INSERT ON `comment` FOR EACH ROW BEGIN
    UPDATE video_data
    SET comment_count = comment_count  + 1
    WHERE video_id = NEW.video_id;
END$$
CREATE DEFINER=`root`@`%` TRIGGER `increment_like_count` AFTER INSERT ON `likes` FOR EACH ROW BEGIN
    UPDATE video_data
    SET like_count = like_count  + 1
    WHERE video_id = NEW.video_id;
END$$
CREATE DEFINER=`root`@`%` TRIGGER `increment_collect_count` AFTER INSERT ON `collect` FOR EACH ROW BEGIN
    UPDATE video_data
    SET collect_count  = collect_count  + 1
    WHERE video_id = NEW.video_id;
END$$
CREATE DEFINER=`root`@`%` TRIGGER `de_collect_count` AFTER DELETE ON `collect` FOR EACH ROW BEGIN
    UPDATE video_data
    SET collect_count = GREATEST(collect_count - 1, 0)
    WHERE video_id = OLD.video_id;
END$$
CREATE DEFINER=`root`@`%` TRIGGER `de_like_count` AFTER DELETE ON `likes` FOR EACH ROW BEGIN
    UPDATE video_data
    SET like_count  = GREATEST(like_count  - 1, 0)
    WHERE video_id = OLD.video_id;
END$$
CREATE DEFINER=`root`@`%` TRIGGER `increment_danmaku_count` AFTER INSERT ON `danmaku` FOR EACH ROW BEGIN
    UPDATE video_data
    SET danmaku_count = danmaku_count  + 1
    WHERE video_id = NEW.video_id;
END$$
DELIMITER ;
