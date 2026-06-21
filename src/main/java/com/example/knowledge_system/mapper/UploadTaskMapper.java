package com.example.knowledge_system.mapper;

import com.example.knowledge_system.entity.UploadTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UploadTaskMapper {

    int insert(UploadTask task);

    int updateStatus(@Param("taskId") String taskId,
                     @Param("status") String status,
                     @Param("errorMsg") String errorMsg);

    UploadTask findByTaskId(@Param("taskId") String taskId);


    // 幂等抢任务：只有 PENDING / FAILED 才能进入 PROCESSING
    int markProcessingIfAllowed(@Param("taskId") String taskId);

    // 初始化任务进度
    int initProgress(@Param("taskId") String taskId,
                     @Param("totalChunks") Integer totalChunks);

    // 成功处理一个 chunk
    int increaseSuccessChunks(@Param("taskId") String taskId);

    // 失败处理一个 chunk
    int increaseFailedChunks(@Param("taskId") String taskId);

    // 增加重试次数
    int increaseRetryCount(@Param("taskId") String taskId,
                           @Param("errorMsg") String errorMsg);

    int markDead(@Param("taskId") String taskId,
                 @Param("errorMsg") String errorMsg);

}