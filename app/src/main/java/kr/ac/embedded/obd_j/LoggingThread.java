package kr.ac.embedded.obd_j;

import android.os.Environment;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

public class LoggingThread extends Thread {

    private static final String TAG = "LoggingThread";

    private static String folderName = Environment.getExternalStorageDirectory().getAbsolutePath() + "/OBDLog/OBD_";
    private String fileName = "";

    public LoggingThread() {
        //초기화 작업
        String now = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
        folderName = folderName + now;
        fileName = "OBD_" + now + ".txt";
        WriteTextFile(folderName, fileName, "");
    }

    public void run() {
        int second = 0;

        while(true) {
            second++;


            try {
                // 동작 구현
                String now = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
                String time = new SimpleDateFormat("HH:mm:ss").format(new Date());
                fileName = "OBD_" + now + ".txt";
                WriteTextFile(folderName, fileName, "\r\n" + time + "  ");

                Thread.sleep(1000);
            } catch (Exception e){
                e.printStackTrace();
            }
        }
    }

    //텍스트내용을 경로의 텍스트 파일에 쓰기
    public void WriteTextFile(String foldername, String filename, String contents) {

        try {
            File dir = new File(foldername);
            //디렉토리 폴더가 없으면 생성함
            if (!dir.exists()) {
                dir.mkdir();
                contents = "시간, 위도, 경도, 엔진 냉매 온도, 엔진 RPM, 속도" +
                        ", 연료 압력, 흡기 온도, MAF 공기 유량, 쓰로틀 위치, 엔진 시동 후 시간, 악셀 페달 위치 D, 악셀 페달 위치 E," +
                        "악셀 페달 위치 F, 엔진 로드 값";
            }
            //파일 output stream 생성
            FileWriter fos = new FileWriter(foldername + "/" + filename, true);
            //파일쓰기
            BufferedWriter writer = new BufferedWriter(fos);
            writer.write(contents);
            writer.flush();

            writer.close();
            fos.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


}
