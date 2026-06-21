package com.example.knowledge_system.mapper;

import com.example.knowledge_system.dto.DocumentChunkVO;
import com.example.knowledge_system.entity.DocumentChunkVector;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface DocumentChunkVectorMapper {
    void insert(DocumentChunkVector chunk);
    List<DocumentChunkVO> searchSimilar(@Param("embedding") String embedding);
    int deleteByFileName(@Param("fileName") String fileName);
    List<String> findAllFileNames();
    int countDistinctFileNames();
    int countChunksByFileName(@Param("fileName") String fileName);
    int expireDocuments();
    String findDocIdByFileName(@Param("fileName") String fileName);

    Integer findMaxVersionByDocId(@Param("docId") String docId);

    int expireActiveByDocId(@Param("docId") String docId);

    int activateVersion(@Param("docId") String docId,
                        @Param("version") Integer version);


    List<DocumentChunkVector> findByDocIdAndVersion(@Param("docId") String docId,
                                                    @Param("version") Integer version);
    int existsByDocIdVersionAndHash(@Param("docId") String docId,
                                    @Param("version") Integer version,
                                    @Param("contentHash") String contentHash);
    List<DocumentChunkVector> findNeighborChunks(
            @Param("fileName") String fileName,
            @Param("version") Integer version,
            @Param("start") int start,
            @Param("end") int end
    );
    List<Map<String, Object>> listVersionsByFileName(@Param("fileName") String fileName);
}
