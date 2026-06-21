package com.example.knowledge_system.util;
import java.util.ArrayList;
import java.util.List;

public class TextSplitter {
    public static List<String> split(String text, int chunkSize){
        List<String> chunks = new ArrayList<>();

        if(text == null || text.isEmpty()){
            return chunks;
        }
        int length = text.length();
        for(int start = 0; start < length; start += chunkSize){
            int end = Math.min(start + chunkSize, length);
            chunks.add(text.substring(start, end));

        }
        return chunks;
    }

}
