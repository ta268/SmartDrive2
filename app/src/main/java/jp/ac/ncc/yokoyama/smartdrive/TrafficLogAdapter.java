package jp.ac.ncc.yokoyama.smartdrive;

import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;

public class TrafficLogAdapter extends RecyclerView.Adapter<TrafficLogAdapter.LogViewHolder> {

    private List<TrafficLog> logList = new ArrayList<>();

    public void addLog(TrafficLog log) {
        logList.add(0, log);
        notifyItemInserted(0);
    }

    @NonNull
    @Override
    public LogViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_shock_log, parent, false);
        return new LogViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull LogViewHolder holder, int position) {
        TrafficLog log = logList.get(position);
        holder.textContent.setText(String.format("[%s]\n%sを検知しました。\n場所: %s", log.getTimestamp(), log.getEventNameJa(), log.getAddress()));

        holder.itemView.setOnClickListener(v -> {
            Intent intent = new Intent(v.getContext(), LogDetailActivity.class);
            intent.putExtra("log_data", log);
            v.getContext().startActivity(intent);
        });
    }

    @Override
    public int getItemCount() {
        return logList.size();
    }

    static class LogViewHolder extends RecyclerView.ViewHolder {
        TextView textContent;

        public LogViewHolder(@NonNull View itemView) {
            super(itemView);
            textContent = itemView.findViewById(R.id.text_log_content);
        }
    }
}
