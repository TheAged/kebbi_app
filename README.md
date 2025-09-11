# kebbi_app

拜託ngrok別關掉 如果不小心關掉記得修正 專案中 RetrofitClient.kt 裡其中一行  private const val BASE_URL = "新的網址/"  -->記得網址最後要加 /



後端啟動順序:   

            (進入資料夾)  : cd ~/桌面/voice-chat-gemini-master/kebbi 

            (把conda關掉): conda deactivate
            
            (進入venv) : source venv/bin/activate

            (啟動 FastAPI 伺服器): uvicorn api_server:app --reload
