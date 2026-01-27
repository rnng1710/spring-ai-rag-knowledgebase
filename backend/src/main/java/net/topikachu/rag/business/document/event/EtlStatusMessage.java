package net.topikachu.rag.business.document.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import net.topikachu.rag.business.document.entity.DocumentStatus;

import java.io.Serializable;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class EtlStatusMessage implements Serializable {
    private String docUuid;
    private String userId; // For targeting specific user clients
    private DocumentStatus status;
    private String message;
}
